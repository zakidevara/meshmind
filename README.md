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

### Sample results

Aggregate scores from a 12-sample run against the on-call assistant (via `RagEvaluationTest` with hand-written ground truths):

```
=== Evaluation Results ===
{'faithfulness': 0.8395, 'answer_relevancy': 0.6942,
 'context_precision': 0.7778, 'context_recall': 0.9167}
```

Per-sample breakdown from `eval\output\eval_results_v3.csv`:

| # | Question | Faith. | Ans. Rel. | Ctx. Prec. | Ctx. Rec. |
|---|---|---:|---:|---:|---:|
| 1 | *How did we fix the ECS task crash-looping with OOMKilled?* | 1.000 | 0.782 | 1.000 | 1.000 |
| 2 | *Our Spring Boot service keeps getting killed by out-of-memory errors. Any recent similar incident and how was it resolved?* | 1.000 | 0.595 | 1.000 | 1.000 |
| 3 | *There is a spike in SQS DLQ for payments. Have we seen this before and what caused it?* | 0.889 | 0.876 | 1.000 | 1.000 |
| 4 | *The checkout endpoint is returning 502s. Is there any similar issue previously? What is the root cause and how to resolve it?* | 1.000 | **0.000** | 1.000 | 1.000 |
| 5 | *Users service is throwing HikariCP connection pool timeout exceptions. What should I check?* | 0.778 | 0.928 | 1.000 | 1.000 |
| 6 | *There is a sudden surge of traffic to our DB causing 100% CPU. What could be the reason?* | 0.818 | 0.926 | **0.000** | **0.000** |
| 7 | *Why is our /api/reports endpoint returning 504 Gateway Timeout?* | 0.875 | 0.866 | **0.333** | 1.000 |
| 8 | *The reporting cron is getting AccessDenied when uploading PDFs to S3. What went wrong?* | 1.000 | 0.777 | 1.000 | 1.000 |
| 9 | *Internal service-to-service calls started failing with certificate expired. How was this fixed last time?* | 0.714 | 0.826 | 1.000 | 1.000 |
| 10 | *Post-deploy NullPointerException on the checkout confirmation page. What was the cause and how was it fixed?* | 1.000 | 0.839 | 1.000 | 1.000 |
| 11 | *The recommendations service is experiencing multi-second JVM GC pauses. What could cause it?* | 1.000 | 0.915 | 1.000 | 1.000 |
| 12 | *How do I configure Prometheus federation across three clusters?* (not in KB) | 0.000 | 0.000 | 0.000 | 1.000 |

**Reading the results:**

- **Faithfulness averages 0.84** — the assistant almost never hallucinates beyond the retrieved summaries. Sample 9 (0.71) is the weakest; the answer adds "proactive approach" framing that isn't literally in the source.
- **Sample 4 (Ans. Rel. = 0.00)** is the most surprising failure. The answer is *correct and topical* (mentions Lambda timeout, rollback, AWS escalation), but the RAGAS metric reverse-generates candidate questions from the answer and compares similarity to the original. The original question is a long, three-part compound phrasing which the reverse-generated questions never match closely enough. **Metric-artifact, not a real quality issue.**
- **Sample 6 (Ctx. Prec./Recall = 0.00)** is a *real* retrieval failure. The question "sudden surge of traffic to our DB causing 100% CPU" pulls back **DynamoDB throttling, Kafka lag, SQS DLQ** — semantically adjacent but not the ground-truth-matching **Redis thundering-herd** thread. The LLM then confidently answers using the wrong incident. This is a signal to add hybrid search or query rewriting.
- **Sample 7 (Ctx. Prec. = 0.33)** shows a ranking issue — the correct nginx thread was retrieved, but was ranked *third* behind two irrelevant API Gateway / intermittent-500s threads. A reranker would fix this.
- **Sample 12 (fallback)** correctly refuses to answer since Prometheus federation isn't in the KB. Faithfulness/relevancy are 0 (fallback text doesn't cite context and doesn't semantically match the question), but **Context Recall = 1.0** because there's nothing to recall — the "ideal" answer is "no info", which is what happened.

**What to fix first:** samples 6 and 7 point at the same root cause — pure vector similarity confuses "DB CPU spike" concepts across incidents. Adding BM25 hybrid search or a cross-encoder reranker would likely lift `context_precision` significantly.

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
