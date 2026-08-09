# RAG Evaluation Report — v3 (ground truth + retrieval-quality metrics enabled)

**Source:** `eval/output/eval_results_v3.csv`
**Input:** `eval/input/eval_samples_v3.jsonl` (12 samples)
**Comparison baseline:** `eval/output/eval_results_v2.csv` (3 samples, no ground truth)
**Judge:** OpenAI `gpt-4o-mini` + `text-embedding-3-small` (via `ragas 0.1.21`)
**Assistant under test:** `OnCallAssistant` with `InMemoryEmbeddingStore` + LLM-summarized Slack ingest

---

## 1. Executive summary

v3 is the run where the eval framework itself matured. Two changes shipped together:

1. **`ground_truth` was added** to every sample in the JSONL, unlocking two new RAGAS metrics: **Context Precision** and **Context Recall**.
2. **Sample size expanded 3 → 12**, drawn from 11 different incident threads plus one deliberately out-of-KB question.

The system-under-test (RAG pipeline) was unchanged from v2 — same Milvus-less `InMemoryEmbeddingStore`, same summarized ingest, same LLM. So *v3 measures the same pipeline more thoroughly*, and the changes vs v2 aggregates are dominated by the wider question set rather than any code change.

| Metric | v2 (3 samples, F+AR only) | v3 (12 samples, 4 metrics) |
|---|---:|---:|
| **Faithfulness** | 0.685 | **0.840** |
| **Answer Relevancy** | 0.571 | **0.694** |
| **Context Precision** | *not measured* | **0.778** |
| **Context Recall** | *not measured* | **0.917** |

**The headline finding: Context Precision and Recall exposed a class of retrieval failure that Faithfulness and Answer Relevancy could not detect** — cases where the LLM confidently answers from a *wrong-but-plausible* retrieved chunk. See §4.

---

## 2. What changed between v2 and v3

| Aspect | v2 | v3 |
|---|---|---|
| Sample count | 3 | 12 |
| Ground truth field | ✗ | ✓ (hand-written per sample) |
| Metrics scored | Faithfulness, Answer Relevancy | + Context Precision, Context Recall |
| Test cases | 3 ad-hoc | 11 curated incident lookups + 1 out-of-KB fallback |
| Ingest pipeline | LLM-summarized (unchanged from v2) | LLM-summarized (unchanged) |
| Embedding store | InMemoryEmbeddingStore (unchanged) | InMemoryEmbeddingStore (unchanged) |
| Judge model | gpt-4o-mini (unchanged) | gpt-4o-mini (unchanged) |

Metric refresher (why the two new ones matter):

- **Context Precision** — for the retrieved contexts, how well are the *relevant* ones ranked ahead of the *irrelevant* ones? Requires ground truth to know what's relevant.
- **Context Recall** — did retrieval bring back *all* the information needed to construct the ground-truth answer? Requires ground truth.

Together they diagnose the retrieval layer, whereas Faithfulness + Answer Relevancy only judge the generation layer.

---

## 3. Per-sample results

| # | Question (abbrev.) | F | AR | CP | CR |
|---|---|---:|---:|---:|---:|
| 1 | How did we fix the ECS OOMKilled? | 1.00 | 0.78 | 1.00 | 1.00 |
| 2 | Spring Boot service killed by OOM — similar incident? *(rephrasing)* | 1.00 | 0.60 | 1.00 | 1.00 |
| 3 | SQS DLQ spike for payments — seen before? | 0.89 | 0.88 | 1.00 | 1.00 |
| 4 | Checkout 502s — similar issue? *(multi-part)* | 1.00 | **0.00** | 1.00 | 1.00 |
| 5 | Users svc HikariCP timeouts — what to check? | 0.78 | 0.93 | 1.00 | 1.00 |
| 6 | Sudden DB traffic, 100% CPU — reason? *(ambiguous)* | 0.82 | 0.93 | **0.00** | **0.00** |
| 7 | /api/reports 504 Gateway Timeout — why? | 0.88 | 0.87 | **0.33** | 1.00 |
| 8 | Reporting cron S3 AccessDenied — what went wrong? | 1.00 | 0.78 | 1.00 | 1.00 |
| 9 | Internal cert expired — how was it fixed? | 0.71 | 0.83 | 1.00 | 1.00 |
| 10 | Post-deploy NPE on checkout confirmation | 1.00 | 0.84 | 1.00 | 1.00 |
| 11 | Recommendations svc JVM GC pauses — cause? | 1.00 | 0.92 | 1.00 | 1.00 |
| 12 | How to configure Prometheus federation? *(out-of-KB)* | **0.00** | **0.00** | **0.00** | 1.00 |

Legend: **F** = Faithfulness, **AR** = Answer Relevancy, **CP** = Context Precision, **CR** = Context Recall. Bolded cells ≤ 0.5.

---

## 4. The key finding: CP/CR exposed what F/AR couldn't

Two samples in particular demonstrate why adding the retrieval-quality metrics changed the picture entirely.

### 4.1 Sample 6 — the "hidden" retrieval failure

The question *"There is a sudden surge of traffic to our DB causing 100% CPU. What could be the reason?"* has a ground-truth answer pointing at the **Redis thundering-herd** incident (cache-miss stampede after a Redis restart, no jitter on reconnect).

**What retrieval actually returned:** the DynamoDB hot-partition thread, the Kafka consumer lag thread, and the SQS DLQ thread — all *semantically adjacent* but none the actual Redis thundering-herd thread.

**What the LLM produced:** a confident, well-structured answer describing DynamoDB throttling due to a hot partition (tenant_id skew, 60% traffic to one enterprise customer).

**How each metric scored this:**

| Metric | Score | What it "saw" |
|---|---:|---|
| Faithfulness | 0.82 | The answer accurately paraphrases the DynamoDB chunk that *was* retrieved. High = the LLM stuck to context. |
| Answer Relevancy | 0.93 | The answer is topically on-point for "why is DB CPU at 100%". Reverse-question generation produces DB-CPU questions that match well. |
| **Context Precision** | **0.00** | None of the retrieved chunks are relevant to the *ground-truth* answer (Redis herd). |
| **Context Recall** | **0.00** | The Redis herd content was *not* retrieved. |

**Without CP/CR, this looks like a perfectly good answer.** With CP/CR, it's clearly a wrong-thread retrieval that the LLM confidently answered from. This is exactly the kind of silent failure that ships to production undetected. **F + AR alone are insufficient to catch it.**

### 4.2 Sample 7 — the ranking failure

The question *"Why is our /api/reports endpoint returning 504 Gateway Timeout?"* has an obvious answer (nginx `proxy-read-timeout: "30"` annotation set from copy-paste). The nginx thread *was* retrieved, but was ranked **third** behind two irrelevant threads (API Gateway 502 and intermittent 500s).

| Metric | Score | Interpretation |
|---|---:|---|
| Faithfulness | 0.88 | The LLM used the correct chunk (which was present, just buried) — fine. |
| Answer Relevancy | 0.87 | Answer directly addresses the question. |
| **Context Precision** | **0.33** | Only 1 of 3 retrieved chunks was relevant (1/3 = 0.33). |
| Context Recall | 1.00 | The needed information *was* retrieved (just not ranked well). |

CP = 0.33 with CR = 1.00 is the signature of a **ranking problem, not a retrieval problem**. The retriever found the right chunk but couldn't rank it above noise. This is a different fix than sample 6's (which needs a smarter retriever); sample 7 needs a **reranker**.

### 4.3 Sample 12 — the fallback ambiguity

The out-of-KB Prometheus federation question is handled correctly by the fallback response *"I cannot find this information in the internal knowledge base."*

- **F = 0** — the fallback text doesn't cite the retrieved context.
- **AR = 0** — reverse-question generation from a non-answer produces generic queries that don't match the specific question.
- **CP = 0** — no retrieved chunk is relevant to the ground-truth "no info available" answer.
- **CR = 1.0** — nothing to recall, so recall is technically perfect.

Three zeros for correct behavior is misleading; a rubric metric for "appropriate refusal" would be a better fit. Sample 12 should be tracked separately from the aggregate.

---

## 5. Continuity from v2

v3 kept two v2 questions in the test set for continuity:

| Question | v2 F | v3 F | v2 AR | v3 AR | Notes |
|---|---:|---:|---:|---:|---|
| Checkout 502s — similar? *(v2 #1 → v3 #4)* | 0.93 | 1.00 | 0.00 | 0.00 | AR still zero — same reverse-question artifact from summarized-answer ending in "unresolved". Confirms this is metric-side, not fixable in code. |
| DB 100% CPU *(v2 #3 → v3 #6)* | 0.63 | 0.82 | 0.94 | 0.93 | F went up (paraphrase drift settled), AR ~flat, but with v3's ground truth we now see CP=CR=0. In v2 this looked healthy; in v3 it's exposed as a retrieval failure. |

The DB 100% CPU pair is instructive: **the same question, same code, gave the impression of health in v2 (2-metric view) and clear failure in v3 (4-metric view)**. The pipeline didn't change — the measurement got sharper.

---

## 6. Analysis by group

### Happy path (samples 1, 3, 5, 8, 10, 11) — strong performance

Six samples score above 0.7 on every metric. Faithfulness averages 0.94 in this group, Answer Relevancy 0.87, Context Precision 1.00, Context Recall 1.00. When the retriever gets the right thread and the LLM operates on it, the pipeline is solid.

### Rephrasing tests (samples 1 vs 2)

Sample 1 asks the ECS OOM question directly ("How did we fix..."); sample 2 rephrases the same intent without keyword overlap ("Our Spring Boot service keeps getting killed..."). Both retrieve the correct thread and score F=1.00, CP=1.00, CR=1.00. **Retrieval is robust to paraphrasing on well-covered incidents.**

Answer Relevancy is lower on sample 2 (0.60 vs 0.78) — the more verbose, "have we seen this before?" framing produces a similarly verbose answer that reverse-generates less cleanly. Style penalty, not a correctness issue.

### Retrieval failures (samples 6 & 7)

Covered in detail in §4. Both point at the same root cause: pure vector similarity struggles with vocabulary-overlapping incidents ("DB CPU spike" matches DynamoDB, Kafka, and SQS threads roughly equally well).

### Paraphrase drift (samples 3, 5, 9)

Three samples show Faithfulness in the 0.71–0.89 range. In each, the answer adds soft framing sentences ("proactive approach helps ensure...", "By addressing the missing index and optimizing the query...") that RAGAS extracts as additional claims to verify. **All answers are factually correct — the metric penalizes reframing.**

### Out-of-KB (sample 12)

Behaves correctly. See §4.3 for why the metrics look catastrophic.

---

## 7. Findings & recommendations

### Confirmed issues

1. **Retrieval-ranking is the primary weakness.** Samples 6 and 7 are two different failure modes of the same underlying issue — pure vector similarity picks semantically-adjacent-but-wrong chunks or fails to rank the right chunk first. **Fix candidates:** hybrid search (BM25 + vector), a cross-encoder reranker, or increasing `maxResults` from 3 to 5.

2. **RAGAS Answer Relevancy penalizes precise, honest answers.** Samples 4 and 12 confirm what v2 hinted at: a strict system prompt + short fallback answers score poorly on AR through no fault of the pipeline. Rubric-based metrics are the long-term fix.

3. **Paraphrase drift in LLM answers costs 10–30 points of Faithfulness** on well-retrieved cases (samples 3, 5, 9). A tighter "quote or paraphrase, don't restructure" system prompt would help.

### New signal exposed by CP/CR

4. **CP/CR make silent retrieval failures visible.** Sample 6 was invisible to F+AR — confidently wrong answers from wrong context looked identical to correct answers from correct context. **This is the single biggest reason to keep ground truth in every future eval run.**

5. **CP vs CR ratio tells you *what* to fix.** Sample 6 (CP=0, CR=0) needs a better retriever. Sample 7 (CP=0.33, CR=1.0) needs a reranker. Same F/AR profile, different fix.

### Non-issues

6. **The fallback case (sample 12) is correct behavior scored as failure.** Should be excluded from aggregate scoring or replaced with a rubric-based refusal check.

---

## 8. What came next

The v3 findings shaped subsequent runs:

- **v5** (18 samples) added six **generation-failure probes** to stress-test the LLM layer: parametric hallucination, extrapolation, contradiction, query misinterpretation, incomplete addressing, and padding. These were designed to complement v3's retrieval-quality focus with generation-quality probes. v5 confirmed that the assistant is vulnerable to parametric-knowledge leakage on well-known topics ("How do I fix a Java OOM?") but robust to leading questions and honest about missing information.
- **v4 was skipped** (dataset iteration only, no run committed).
- The retrieval-ranking findings from samples 6 and 7 remain the top open backlog item across all runs.
