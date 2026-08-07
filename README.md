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
- Google AI Studio API key (if using Gemini)

1. Configure your environment variables:
Create a `.env` file in the root directory with the following content:
```
# For Gemini
GEMINI_API_KEY=your_gemini_api_key_here
```

2. Build and run the application:
```bash
mvn clean install
mvn spring-boot:run
```

3. Access the application to test using chat interface:
Open your web browser and navigate to `http://localhost:63342/meshmind/static/index.html`.

4. Use curl to test the API:

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

MeshMind logs every query's retrieved contexts and generated answer to `eval_samples.jsonl` in the project root. Use the RAGAS evaluation script in `eval/` to measure pipeline quality.

### Metrics
- **Faithfulness** — is the answer grounded in the retrieved context (no hallucination)?
- **Answer Relevancy** — is the answer actually relevant to the question?

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

1. Start the app and make several queries to populate `eval_samples.jsonl`.

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

The script prints aggregate scores to the console and saves per-sample results to `eval\eval_results.csv`.

### Sample results

Aggregate scores from a two-sample run against the on-call assistant:

```
=== Evaluation Results ===
{'faithfulness': 0.6333, 'answer_relevancy': 0.6169}
```

Per-sample breakdown from `eval\eval_results.csv`:

| # | Question | Faithfulness | Answer Relevancy |
|---|---|---:|---:|
| 1 | *have there been any issue causing from API Gateway 5xx recently?* | 0.667 | 0.808 |
| 2 | *I am experiencing OOM in one of my service. Is there any similar incident recently, what is the root cause and how do you resolve it?* | 0.600 | 0.426 |

**Reading the results:**
- Sample 1 scores well on relevancy (0.81) but only 0.67 on faithfulness — the answer mentions "escalating to AWS support" which is supported by context, but some framing claims aren't fully grounded.
- Sample 2 has low relevancy (0.43) because the answer partially conflates two different incidents (OOM vs. SQS DLQ spike) that were both retrieved. This is a retrieval-quality signal: the assistant is being led astray by loosely related contexts.

### Overriding paths and models

```powershell
$env:EVAL_INPUT  = "path\to\eval_samples.jsonl"
$env:EVAL_OUTPUT = "path\to\output.csv"

# Gemini model overrides
$env:GEMINI_MODEL = "gemini-2.5-flash"
$env:GEMINI_EMBED_MODEL = "models/text-embedding-004"

# OpenAI model overrides
$env:OPENAI_MODEL = "gpt-4o-mini"
$env:OPENAI_EMBED_MODEL = "text-embedding-3-small"

python eval\evaluate.py
```
