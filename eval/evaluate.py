#!/usr/bin/env python3
"""Evaluates RAG pipeline quality using RAGAS faithfulness and answer relevancy metrics."""

import json
import os
import re
import shutil
import sys
import typing as t
from datetime import datetime
from pathlib import Path

from datasets import Dataset
from langchain_core.callbacks import Callbacks
from langchain_core.outputs import LLMResult
from langchain_core.prompt_values import PromptValue
from langchain_google_genai import ChatGoogleGenerativeAI, GoogleGenerativeAIEmbeddings
from ragas import evaluate
from ragas.embeddings import LangchainEmbeddingsWrapper
from ragas.llms import LangchainLLMWrapper
from ragas.metrics import answer_relevancy, context_precision, context_recall, faithfulness


class GeminiSafeLLMWrapper(LangchainLLMWrapper):
    """Sets temperature on the underlying LLM rather than passing it as a call-time kwarg.

    langchain-google-genai 1.0.10 forwards unknown kwargs through to the low-level
    GenerativeServiceClient.generate_content(), which raises TypeError on 'temperature'.
    Ragas 0.1.21 always passes temperature=, so we intercept and apply it as an attribute.
    """

    def _apply_temperature(self, temperature: t.Optional[float], n: int) -> None:
        if temperature is None:
            temperature = self.get_temperature(n=n)
        if hasattr(self.langchain_llm, "temperature"):
            self.langchain_llm.temperature = temperature

    def generate_text(self, prompt: PromptValue, n: int = 1,
                      temperature: t.Optional[float] = None,
                      stop: t.Optional[t.List[str]] = None,
                      callbacks: Callbacks = None) -> LLMResult:
        self._apply_temperature(temperature, n)
        return self.langchain_llm.generate_prompt(
            prompts=[prompt] * n, stop=stop, callbacks=callbacks,
        )

    async def agenerate_text(self, prompt: PromptValue, n: int = 1,
                              temperature: t.Optional[float] = None,
                              stop: t.Optional[t.List[str]] = None,
                              callbacks: Callbacks = None) -> LLMResult:
        self._apply_temperature(temperature, n)
        return await self.langchain_llm.agenerate_prompt(
            prompts=[prompt] * n, stop=stop, callbacks=callbacks,
        )

INPUT_DIR = Path(__file__).parent / "input"
OUTPUT_DIR = Path(__file__).parent / "output"
JSONL_PATH = Path(os.environ.get("EVAL_INPUT", INPUT_DIR / "eval_samples.jsonl"))
OUTPUT_PATH_OVERRIDE = os.environ.get("EVAL_OUTPUT")


def next_run_id() -> tuple[int, str]:
    """Scan the input dir for prior eval_samples_v{N}*.jsonl files and return (N+1, timestamp)."""
    pattern = re.compile(r"eval_samples_v(\d+).*\.jsonl$")
    max_n = 0
    if INPUT_DIR.exists():
        for f in INPUT_DIR.glob("eval_samples_v*.jsonl"):
            m = pattern.match(f.name)
            if m:
                max_n = max(max_n, int(m.group(1)))
    return max_n + 1, datetime.now().strftime("%Y%m%d_%H%M%S")


def load_samples(path: Path) -> list[dict]:
    if not path.exists():
        print(f"Error: {path} not found. Run the Spring app and make some queries first.", file=sys.stderr)
        sys.exit(1)
    samples = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                samples.append(json.loads(line))
    return samples


def build_judge():
    """Returns (llm, embeddings) wrappers based on the EVAL_JUDGE env var (gemini|openai)."""
    judge = os.environ.get("EVAL_JUDGE", "gemini").lower()

    if judge == "openai":
        from langchain_openai import ChatOpenAI, OpenAIEmbeddings
        api_key = os.environ.get("OPENAI_API_KEY")
        if not api_key:
            print("Error: OPENAI_API_KEY environment variable is not set.", file=sys.stderr)
            sys.exit(1)
        model = os.environ.get("OPENAI_MODEL", "gpt-4o-mini")
        embed_model = os.environ.get("OPENAI_EMBED_MODEL", "text-embedding-3-small")
        print(f"Judge: OpenAI ({model}, {embed_model})")
        llm = LangchainLLMWrapper(ChatOpenAI(model=model, openai_api_key=api_key))
        embeddings = LangchainEmbeddingsWrapper(OpenAIEmbeddings(model=embed_model, openai_api_key=api_key))
        return llm, embeddings

    if judge == "gemini":
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            print("Error: GEMINI_API_KEY environment variable is not set.", file=sys.stderr)
            sys.exit(1)
        model = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")
        embed_model = os.environ.get("GEMINI_EMBED_MODEL", "models/text-embedding-004")
        print(f"Judge: Gemini ({model}, {embed_model})")
        llm = GeminiSafeLLMWrapper(ChatGoogleGenerativeAI(model=model, google_api_key=api_key))
        embeddings = LangchainEmbeddingsWrapper(GoogleGenerativeAIEmbeddings(model=embed_model, google_api_key=api_key))
        return llm, embeddings

    print(f"Error: unknown EVAL_JUDGE '{judge}'. Use 'gemini' or 'openai'.", file=sys.stderr)
    sys.exit(1)


def main():
    samples = load_samples(JSONL_PATH)
    if not samples:
        print(f"No samples found in {JSONL_PATH}.", file=sys.stderr)
        sys.exit(1)

    version, timestamp = next_run_id()

    # snapshot the current input to a versioned copy for auditability
    snapshot_path = INPUT_DIR / f"eval_samples_v{version}_{timestamp}.jsonl"
    INPUT_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(JSONL_PATH, snapshot_path)
    print(f"Snapshotted input to {snapshot_path.name}")

    # versioned output path (or user override if EVAL_OUTPUT set)
    output_path = Path(OUTPUT_PATH_OVERRIDE) if OUTPUT_PATH_OVERRIDE \
        else OUTPUT_DIR / f"eval_results_v{version}_{timestamp}.csv"

    have_ground_truth = all(s.get("ground_truth") for s in samples)
    print(f"Loaded {len(samples)} sample(s) from {JSONL_PATH}. Ground truth present: {have_ground_truth}")

    # ragas 0.1.x expects: question, contexts, answer (+ ground_truth for context precision/recall)
    dataset = Dataset.from_list([
        {
            "question": s["question"],
            "contexts": s["contexts"],
            "answer": s["answer"],
            "ground_truth": s.get("ground_truth") or "",
        }
        for s in samples
    ])

    llm, embeddings = build_judge()

    metrics = [faithfulness, answer_relevancy]
    if have_ground_truth:
        metrics += [context_precision, context_recall]
    else:
        print("Skipping context_precision + context_recall (no ground_truth on all samples).")

    result = evaluate(
        dataset=dataset,
        metrics=metrics,
        llm=llm,
        embeddings=embeddings,
    )

    print("\n=== Evaluation Results ===")
    print(result)

    df = result.to_pandas()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(output_path, index=False)
    print(f"\nPer-sample results saved to {output_path}")


if __name__ == "__main__":
    main()