# MeshMind: Local RAG Assistant
An AI assistant built with Spring Boot, LangChain4j, and Gemini/Ollama. Designed to query internal wikis and Slack thread history.

---

## Features
- **Retrieval-Augmented Generation (RAG)**: Automatically ingests and indexes structured data (such as Slack on-call archives and internal wikis) to ground LLM responses in factual, internal company knowledge.

- **Strict Guardrails**: Configured with strict system prompts to prevent hallucinations and output a fallback message if information is missing from the knowledge base.

- **Flexible LLM Backend**: Easily toggle between cloud-hosted models (Google Gemini Flash) and locally hosted LLMs.

---

## Tech Stack
- Backend: Java 21, Spring Boot (Web)
- AI Orchestration: LangChain4j 1.17.2
- LLM Provider: Google AI Gemini (gemini-2.5-flash) / Ollama
- Frontend: Vanilla HTML, CSS, JavaScript

---

## Getting Started
### Prerequisites
- Java 21
- Maven 3.8+
- Docker + Docker Compose (for local Milvus)
- Google AI Studio API key (if using Gemini) and/or OpenAI API key

1. Start the vector store (Milvus + etcd + minio):
```powershell
docker compose up -d
```
Wait ~30–60s for Milvus to become healthy (`docker compose ps` should show all three as healthy). Data persists under `./volumes/`. To wipe: `docker compose down -v` then delete the `volumes/` directory.

2. Configure your environment variables:
Create a `.env` file in the root directory with the following content:
```
# For OpenAI (default LLM provider)
OPENAI_API_KEY=your_openai_api_key_here
# For Gemini
GEMINI_API_KEY=your_gemini_api_key_here
```

**Switch LLM provider** via `application.yaml` (no code change needed):
```yaml
app:
  llm:
    provider: openai   # or: gemini
```

3. Build and run the application:
```bash
mvn clean install
mvn spring-boot:run
```
On first run, `SlackDataLoader` embeds all threads into Milvus. On subsequent runs it detects existing data and skips ingestion. To force a re-ingest: `docker compose down -v` and `rm -rf volumes/`.

4. Access the application to test using chat interface:
Open your web browser and navigate to `http://localhost:63342/meshmind/static/index.html`.

5. Use curl to test the API:

**Bash / CMD:**
```bash
curl ^"http://localhost:8080/api/ai/ask/oncall" ^
  -H ^"Content-Type: text/plain^" ^
  --data-raw ^"have there been any issue causing from API Gateway 5xx recently?^"
```

**PowerShell (Invoke-RestMethod):**
```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/ai/ask/oncall" `
  -Method POST `
  -ContentType "text/plain" `
  -Body "have there been any issue causing from API Gateway 5xx recently?"
```

**PowerShell (curl.exe):**
```powershell
curl.exe -X POST "http://localhost:8080/api/ai/ask/oncall" `
  -H "Content-Type: text/plain" `
  --data-raw "have there been any issue causing from API Gateway 5xx recently?"
```

---

## Evaluating the RAG Pipeline

MeshMind logs every query's retrieved contexts and generated answer to `eval/input/eval_samples.jsonl`. Use the RAGAS evaluation script in `eval/` to measure pipeline quality.

### Metrics
- **Faithfulness** — is the answer grounded in the retrieved context (no hallucination)? *(no ground truth needed)*
- **Answer Relevancy** — is the answer actually relevant to the question? *(no ground truth needed)*
- **Context Precision** — are the *relevant* retrieved chunks ranked ahead of the irrelevant ones? *(needs ground truth)*
- **Context Recall** — did retrieval bring back all the information needed to answer? *(needs ground truth)*

### Setup

1. Create and activate a virtual environment:

```powershell
python -m venv eval\.venv
eval\.venv\Scripts\Activate.ps1
```

2. Install dependencies:

```powershell
pip install -r eval\requirements.txt
```

### Running the evaluation

1. Populate `eval\input\eval_samples.jsonl`. Two options:

   **Option A — Curated test set (recommended):** run `RagEvaluationTest`, which iterates through a fixed set of `(question, ground_truth)` prompts against the on-call assistant and writes them to `eval\input\eval_samples.jsonl`. This is what enables the context-precision and context-recall metrics.
   ```powershell
   .\mvnw.cmd test "-Dtest=RagEvaluationTest" "-Drun.eval.test=true"
   ```
   Requires Milvus + Ollama running and the app's LLM provider configured. Overwrites `eval\input\eval_samples.jsonl` on each run.

   **Option B — Ad-hoc queries:** start the app normally and hit `/api/ai/ask/oncall`. Each request appends to `eval\input\eval_samples.jsonl` (without ground truth, so only faithfulness + answer_relevancy will be scored).


2. Choose a judge LLM by setting `EVAL_JUDGE` — `gemini` (default) or `openai`.

**Option A — Gemini (default):**
```powershell
$env:EVAL_JUDGE = "gemini"
$env:GEMINI_API_KEY = "your_gemini_api_key_here"
python eval\evaluate.py
```

**Option B — OpenAI:**
```powershell
$env:EVAL_JUDGE = "openai"
$env:OPENAI_API_KEY = "your_openai_api_key_here"
python eval\evaluate.py
```

The script prints aggregate scores to the console and saves per-sample results to `eval\output\eval_results.csv`.

### Evaluation history

Aggregate scores across runs. Full per-sample analysis and findings live in `eval/report/`.

| Run | Samples | Faithfulness | Answer Rel. | Ctx. Prec. | Ctx. Rec. | Report |
|---|---:|---:|---:|---:|---:|---|
| v1 — baseline (raw chat ingest) | 3 | 0.464 | 0.569 | *n/a* | *n/a* | [report_v1.md](eval/report/report_v1.md) |
| v2 — added LLM summarization | 3 | 0.685 | 0.571 | *n/a* | *n/a* | [report_v2.md](eval/report/report_v2.md) |
| v3 — added ground truth + expanded to 12 samples | 12 | 0.840 | 0.694 | 0.778 | 0.917 | [report_v3.md](eval/report/report_v3.md) |
| v4 — added 6 generation-failure probes | 18 | 0.807 | 0.748 | 0.731 | 0.857 | [report_v4.md](eval/report/report_v4_20260809_170823.md) |
| v5 — hybrid search (vector + BM25 with RRF) | 18 | 0.789 | 0.695 | **0.756** | **0.912** | [report_v5.md](eval/report/report_v5_20260809_183105.md) |

**Highlights:**

- **v1 → v2** (summarization): +22 pts Faithfulness. Rescued a previously-broken retrieval case (verbose "DB CPU high" query) from the fallback trap.
- **v3** (ground truth added): Context Precision + Recall metrics exposed a class of *silent* retrieval failures — the LLM confidently answering from a wrong-but-plausible chunk — that F+AR couldn't detect.
- **v4** (probe cases added): confirmed real parametric-knowledge leakage on well-known topics ("How do I fix a Java OOM?") but robust behavior on leading questions and out-of-KB queries.
- **v5** (hybrid search): the flagship retrieval failure (sample 6 — "DB 100% CPU" mis-routed to DynamoDB instead of Redis herd) is fixed: CP 0.00 → 0.50, CR 0.00 → 1.00. Parametric-leakage probe (sample 13) also improved (F 0.27 → 0.50). Small dip in F/AR is the cost of a larger fused context (8 chunks vs 3) — next step is a cross-encoder reranker to trim noise.
- **Top open issue:** rank quality on cases where the right chunk is retrieved but buried (sample 7). A cross-encoder reranker on top of the RRF fusion is the natural next fix.

Each report follows the same structure: executive summary → what changed vs. prior run → per-sample table → group analysis → findings → what came next.

### Overriding paths and models

```powershell
$env:EVAL_INPUT  = "eval\input\eval_samples.jsonl"
$env:EVAL_OUTPUT = "path\to\output.csv"

# Gemini model overrides
$env:GEMINI_MODEL = "gemini-2.5-flash"
$env:GEMINI_EMBED_MODEL = "models/text-embedding-004"

# OpenAI model overrides
$env:OPENAI_MODEL = "gpt-4o-mini"
$env:OPENAI_EMBED_MODEL = "text-embedding-3-small"

python eval\evaluate.py
```
