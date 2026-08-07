#!/usr/bin/env python3
"""Evaluates RAG pipeline quality using RAGAS faithfulness and answer relevancy metrics."""

import json
import os
import sys
from pathlib import Path

import typing as t

from datasets import Dataset
from langchain_core.callbacks import Callbacks
from langchain_core.outputs import LLMResult
from langchain_core.prompt_values import PromptValue
from langchain_google_genai import ChatGoogleGenerativeAI, GoogleGenerativeAIEmbeddings
from ragas import evaluate
from ragas.embeddings import LangchainEmbeddingsWrapper
from ragas.llms import LangchainLLMWrapper
from ragas.metrics import answer_relevancy, faithfulness


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

JSONL_PATH = Path(os.environ.get("EVAL_INPUT", Path(__file__).parent.parent / "eval_samples.jsonl"))
OUTPUT_PATH = Path(os.environ.get("EVAL_OUTPUT", Path(__file__).parent / "eval_results.csv"))


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

    print(f"Loaded {len(samples)} sample(s) from {JSONL_PATH}")

    # ragas 0.1.x expects: question, contexts, answer
    dataset = Dataset.from_list([
        {
            "question": s["question"],
            "contexts": s["contexts"],
            "answer": s["answer"],
        }
        for s in samples
    ])

    llm, embeddings = build_judge()

    result = evaluate(
        dataset=dataset,
        metrics=[faithfulness, answer_relevancy],
        llm=llm,
        embeddings=embeddings,
    )

    print("\n=== Evaluation Results ===")
    print(result)

    df = result.to_pandas()
    df.to_csv(OUTPUT_PATH, index=False)
    print(f"\nPer-sample results saved to {OUTPUT_PATH}")


if __name__ == "__main__":
    main()