# RAG Evaluation Report — v1 (baseline, raw chat ingest)

**Source:** `eval/output/eval_results_v1.csv`
**Input:** `eval/input/eval_samples_v1.jsonl` (3 samples)
**Comparison baseline:** None — this is the first evaluation run.
**Judge:** OpenAI `gpt-4o-mini` + `text-embedding-3-small` (via `ragas 0.1.21`)
**Assistant under test:** `OnCallAssistant` with `InMemoryEmbeddingStore` + **raw Slack chat ingest** (no summarization yet)

---

## 1. Executive summary

v1 is the **first evaluation** of the RAG pipeline. The system ingested raw Slack thread messages verbatim (typos, `<@Uxxxx>` tags, emojis, casual back-and-forth) and embedded each thread as a single document. Three ad-hoc questions were run through the assistant and scored with the two RAGAS metrics that don't require ground truth.

| Metric | v1 |
|---|---:|
| **Faithfulness** | 0.464 |
| **Answer Relevancy** | 0.569 |

**Headline:** the aggregate looks weak, but the average is dragged down entirely by **one hard fail** (sample 2, F=AR=0.0). The other two samples score reasonably well. The signal from this run is not "our system is bad" — it's "our system is **fragile to phrasing**", and the single failing case pointed directly at the fix (LLM-based summarization at ingest, shipped in v2).

There is no ground truth in v1, so **Context Precision** and **Context Recall** are not scored. Both were added in v3.

---

## 2. Pipeline state at v1

The system-under-test at this point:

| Component | v1 setup |
|---|---|
| Chat model | OpenAI `gpt-4o-mini` |
| Embedding model | Ollama `nomic-embed-text` (768d) |
| Embedding store | `InMemoryEmbeddingStore` (no persistence) |
| Content retriever | `EmbeddingStoreContentRetriever` with `maxResults=3`, `minScore=0.7` |
| Ingest | Raw Slack chat lines concatenated per thread, one thread = one document |
| Slack thread source | `slack_oncall_export.json` (17 curated threads with realistic typos/tags) |

The critical ingest snippet at v1:

```java
String threadContent = threadMessages.stream()
    .map(m -> m.user() + ": " + m.text())
    .collect(Collectors.joining("\n"));
```

Every retrieved chunk sent to the LLM looked like:

```
U2222B: :siren: huge spike in the SQS DLQ for payment processing
U4444D: on it
U4444D: DLQ at 12k, sampled a few, all fail json parsing on `refund_reason`
U2222B: the payments team pushed something last night?
...
```

**High noise, low signal density.**

---

## 3. Per-sample results

| # | Question | F | AR | Kind |
|---|---|---:|---:|---|
| 1 | *The checkout endpoint is returning 502s. Is there any similar issue previously? what is the root cause and how to resolve it?* | 0.615 | 0.817 | multi-part, high keyword overlap |
| 2 | *There is a sudden surge of traffic to our DB causing CPU utilization to be high. What could be the reason?* | **0.000** | **0.000** | verbose phrasing, low keyword density |
| 3 | *There is a sudden surge of traffic to our DB causing 100% CPU. What could be the reason?* | 0.778 | 0.889 | terse phrasing, high keyword match |

Bolded rows are hard failures.

---

## 4. Analysis by sample

### 4.1 Sample 1 — API Gateway 502 (multi-part) — decent

**F=0.62, AR=0.82.** The answer correctly identified the previous API Gateway 502 incident (Lambda timeout at 30s, escalated to AWS). The multi-part question was addressed cleanly.

**Why Faithfulness is only 0.62:** the answer paraphrases and summarizes the raw chat into a coherent narrative (*"the root cause appears to be related to the Lambda timing out"*). RAGAS extracts atomic claims from that narrative and compares each to the raw chat context. A few framing sentences — "the next steps included", "in summary" — read as claims not literally present in the source. **Not a real quality issue — the answer is correct, just glossed.**

### 4.2 Sample 2 — DB CPU high (verbose) — hard fail

**F=0, AR=0.** The assistant returned the fallback: *"I cannot find this information in the internal knowledge base."*

**Why this failed:** the query *"sudden surge of traffic to our DB causing CPU utilization to be high"* uses common but non-specific vocabulary. In raw-chat form, the DynamoDB thread that would have matched this looks like:

```
U4444D: :siren: dynamo throttling on the sessions table, ~2% reads failing
U1111A: cloudwatch shows one partition getting 80% of traffic. hot partition
U4444D: the partition key is tenant_id...
```

The specific words "DB", "CPU", "traffic surge" are almost entirely absent from that chat text. Semantic similarity to the query didn't clear the `minScore=0.7` threshold, retrieval returned nothing usable, and the strict system prompt correctly refused to answer.

**This is the single most important finding of v1.** It's not that the pipeline is broken — it's that raw chat context has too much noise per unit of retrievable signal. Sample 3 confirms the diagnosis (see §4.3).

### 4.3 Sample 3 — DB 100% CPU (terse) — good

**F=0.78, AR=0.89.** Same question intent as sample 2, but phrased differently. The word *"100% CPU"* appears verbatim in the Redis thundering-herd thread (`U2222B: All services fired their cache-miss fallback simultaneously. DB CPU pegged at 100%`), so this time retrieval clears the threshold.

**The sample-2-vs-sample-3 gap is diagnostic.** Same intent, same underlying data, very different outcome depending on whether the user's phrasing happens to overlap with keywords in the raw chat. This is fragile.

---

## 5. Findings that motivated the next iteration

Three concrete signals came out of v1:

1. **Retrieval is phrasing-sensitive on noisy chat data.** Sample 2 vs. sample 3 shows the pipeline is one keyword away from silently returning a fallback instead of the correct answer.
2. **Faithfulness dips (~0.6) on cases that work** are driven by the LLM reshaping raw chat into structured narrative. Not a real quality issue, but it shows the LLM has to do extra work to produce a coherent answer from unstructured context.
3. **The 3-sample dataset is far too small to distinguish signal from noise.** One failing case can move the aggregate by 30+ points.

### Direct implications

- **Signal-boost the ingest layer.** If raw chat is too noisy to retrieve reliably, preprocess it into a structured form before embedding. **This became the v2 change: adding `SlackThreadSummarizer` between ingest and embedding.**
- **Expand the eval suite.** Move beyond ad-hoc probes to a curated question set. **This became the v3 change (12 samples).**
- **Add ground truth to enable Context Precision / Context Recall.** F+AR alone can't distinguish "retrieval failed" from "retrieval succeeded but answer is weak" — critical since sample 2 is a retrieval failure but sample 1's low Faithfulness is a generation-side artifact. **This also became the v3 change.**

---

## 6. Metric interpretation notes (v1 caveats)

Two metric behaviors observed here are worth flagging, since they persist in later runs:

- **Fallback response scores 0 on both metrics** (sample 2). The metric can't distinguish "the assistant correctly refused because it had no information" from "the assistant failed to answer a question it should have answered." In v1 the fallback was fired *incorrectly* (the info existed but wasn't retrieved), so the 0/0 is deserved — but the same 0/0 pattern shows up in later runs on genuinely out-of-KB questions where the refusal is correct behavior.
- **Faithfulness penalizes reshaping.** Answering in the model's own words — even when factually grounded — costs a few tenths on F. This will keep showing up on well-answered cases in every later run.

---

## 7. What came next

- **v2** — introduced `SlackThreadSummarizer`. Every Slack thread is passed through the LLM at ingest time and rewritten into a structured `Issue / Root cause / Investigation / Resolution / Tags` block before embedding. On the same 3 questions, this delivered **F: 0.464 → 0.685 (+0.22)** and, more importantly, **rescued sample 2 from the fallback trap** (F: 0 → 0.5, AR: 0 → 0.77). See `report_v2.md`.
- **v3** — expanded to 12 samples, added ground truth, enabled Context Precision + Context Recall. Immediately exposed a *different* failure class — silent wrong-thread retrieval on ambiguous queries — that F+AR couldn't see. See `report_v3.md`.
- **v4** — added 6 generation-failure probes (parametric hallucination, extrapolation, contradiction, etc.) to stress-test the LLM layer. See `report_v4_20260809_170823.md`.

v1's role in the story is as **the baseline that identified the ingest layer as the highest-leverage fix**. The single failing case (sample 2) was worth more than either of the passing cases, because it pointed at a concrete, testable change (summarization) that improved every subsequent run.
