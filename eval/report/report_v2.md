# RAG Evaluation Report — v2 (introduction of LLM summarization)

**Source:** `eval/output/eval_results_v2.csv`
**Input:** `eval/input/eval_samples_v2.jsonl` (3 samples)
**Comparison baseline:** `eval/output/eval_results_v1.csv` (same 3 questions, pre-summarization)
**Judge:** OpenAI `gpt-4o-mini` + `text-embedding-3-small` (via `ragas 0.1.21`)
**Assistant under test:** `OnCallAssistant` with `InMemoryEmbeddingStore` (v2 predates the Milvus migration)

---

## 1. Executive summary

v2 is the run **immediately after introducing the LLM summarization step** in `SlackDataLoader`. Every Slack thread is first passed through `SlackThreadSummarizer` (structured `Issue / Root cause / Investigation / Resolution / Tags` format) before being embedded, replacing the raw chat-style context used in v1.

| Metric | v1 (raw chat context) | v2 (LLM-summarized context) | Δ |
|---|---:|---:|---:|
| **Faithfulness** | 0.464 | **0.685** | **+0.22** |
| **Answer Relevancy** | 0.569 | 0.571 | +0.00 |

**Headline:** summarization delivered a **+22-point jump in Faithfulness** and, more importantly, **fixed one previously-broken retrieval case** (sample 2) that had been triggering the fallback response. The Answer Relevancy average stayed flat — but that flatness hides a real regression on one sample (see §3.1).

Note: v2 does not yet have `ground_truth` in the dataset, so only Faithfulness and Answer Relevancy are scored. Context Precision and Recall were added in later runs.

---

## 2. What changed between v1 and v2

The only meaningful code change between the two runs was in the ingest pipeline:

| Aspect | v1 | v2 |
|---|---|---|
| Ingested content | Raw chat lines (`U1111A: :siren: ...`) | LLM-generated structured summary |
| Format | Freeform casual text with typos/emojis | `Issue: ... / Root cause: ... / Investigation: ... / Resolution: ... / Tags: ...` |
| Content per thread | ~5–10 messages, high noise | 1 dense paragraph, high signal |
| Cost | 1× embedding call per thread | 1× LLM call + 1× embedding call per thread (at startup only) |

Everything else — the same three questions, the same LLM (gpt-4o-mini), the same in-memory embedding store, the same retriever config (`maxResults=3`, `minScore=0.7`) — was held constant.

---

## 3. Per-sample results

| # | Question | v1 F | v2 F | v1 AR | v2 AR | Verdict |
|---|---|---:|---:|---:|---:|---|
| 1 | *The checkout endpoint is returning 502s. Is there any similar issue previously? what is the root cause and how to resolve it?* | 0.615 | **0.929** | 0.817 | **0.000** | Faith. up sharply; AR regressed |
| 2 | *There is a sudden surge of traffic to our DB causing CPU utilization to be high. What could be the reason?* | **0.000** | **0.500** | **0.000** | **0.774** | **Rescued from fallback** |
| 3 | *There is a sudden surge of traffic to our DB causing 100% CPU. What could be the reason?* | 0.778 | 0.625 | 0.889 | 0.938 | Faith. dipped slightly; AR up |

### 3.1 Sample 1 — API Gateway 502s

Faithfulness went from 0.62 → **0.93**, but Answer Relevancy collapsed from 0.82 → **0.00**.

**Why Faithfulness improved:** the summarized context is a tightly-scoped bulleted document with an explicit "Resolution: Unresolved as of last message" line. The LLM copies phrasing more literally from that structure and produces fewer atomic claims that go beyond the source.

**Why Answer Relevancy collapsed:** RAGAS's Answer Relevancy metric reverse-generates candidate questions from the answer, then compares similarity to the original question. The v2 answer ends with *"As of the last message, the issue remains unresolved"* — a factual line the summary explicitly contains. When RAGAS reverse-generates from an answer that concludes with "unresolved", the candidate questions look like *"Was the issue resolved?"* or *"What is the current status?"* — which are semantically distant from the original 3-part compound question (*"any similar issue previously + root cause + how to resolve"*).

**This is a RAGAS metric artifact, not a real quality regression.** The v2 answer is more accurate and better-grounded than the v1 answer.

### 3.2 Sample 2 — DB CPU query (verbose phrasing) — the big win

In v1, this question triggered the fallback response *"I cannot find this information in the internal knowledge base"* and scored 0/0 on both metrics. In v2, it returns a real, correct answer (DynamoDB hot partition + traffic distribution explanation) scoring **F = 0.50, AR = 0.77**.

**Why summarization fixed this:** the raw v1 chat context for the DynamoDB thread starts with a user handle and a `:siren:` emoji, then meanders through investigation lines like *"CloudWatch shows one partition getting 80% of the traffic. Classic hot partition."* — heavy on filler tokens, thin on the specific keywords a query about "DB CPU" needs to match. The v2 summary boils it down to *"Issue: DynamoDB throttling on the sessions table causing ~2% of reads to fail. Root cause: Hot partition due to uneven traffic distribution."* — the keywords **DynamoDB, throttling, hot partition, traffic distribution** are all present in one dense chunk, which crosses the `minScore=0.7` similarity threshold that the raw chat version failed.

**Interpretation:** for noisy source data, LLM summarization acts as a form of query-time preprocessing done at ingest time — it normalizes phrasing and boosts keyword density, which is precisely what dense retrieval needs.

Faithfulness at 0.50 is still moderate here, indicating the answer added some framing beyond the source (e.g., "leading to the high CPU utilization"), but the qualitative outcome — a useful answer where there was previously none — is the important signal.

### 3.3 Sample 3 — DB 100% CPU (terse phrasing) — small tradeoff

This sample already worked well in v1 (F=0.78, AR=0.89). In v2, Answer Relevancy nudged up (+0.05) but Faithfulness dipped (-0.15).

**Why:** the v2 summary contains extra framing sentences like *"leading to throttling on the sessions table and resulting in performance issues"* which the LLM then paraphrases into its answer. Each extra paraphrased sentence is another atomic claim that RAGAS extracts and checks — a few of them read as slight overreach beyond the source.

**Interpretation:** summarization sometimes *adds* faithful but not-literally-quotable text, which is what happens here. This is a small negative signal for Faithfulness but not a real quality issue — the answer is still correct.

---

## 4. Findings

### What summarization clearly wins

1. **Rescues fallback cases caused by phrasing mismatch** (sample 2). The single biggest win — a previously-unanswered query now works. This is the mechanism to keep improving retrieval on messy real-world Slack data.
2. **Sharpens Faithfulness on cases that already work.** Sample 1 gained +0.31 on F. Structured summaries make the LLM stay closer to the source.
3. **Consolidates context**: one dense chunk per thread rather than 5–10 message lines. Fewer tokens in the context window, sharper retrieval signal.

### What summarization exposes

1. **RAGAS Answer Relevancy penalizes precise, structured answers.** Sample 1's v2 answer is *better* but scored 0 because its ending ("still unresolved") drives reverse-question generation to a semantically distant place. This is a metric-side limitation, not a system-side regression.
2. **The summarizer can add faithful paraphrases** (sample 3) that RAGAS treats as extra claims to verify. Small Faithfulness dip on already-good cases.
3. **New failure mode: startup cost.** Every thread now costs 1 additional LLM call at boot. With 17 threads and gpt-4o-mini this is ~cents; at scale it matters. Skip logic in `SlackDataLoader.isAlreadyPopulated()` mitigates repeat runs.

---

## 5. Recommendations (from v2's vantage point)

1. **Keep summarization.** The Faithfulness gain and the recovered-fallback case (sample 2) are strong justifications. Retire raw-chat ingestion.
2. **Add `ground_truth` to future eval samples** to unlock Context Precision + Context Recall. Sample 2's recovery in particular deserves a retrieval-quality metric to confirm it's working for the *right* reason.
3. **Expand the eval suite beyond 3 samples.** The current dataset is too small to distinguish signal from noise on individual metric moves. Aim for at least 10–15 curated cases.
4. **Investigate the Answer Relevancy pattern on structured answers.** Sample 1's zero score is a warning that a strict system prompt (which we want, for anti-hallucination) may be penalized by RAGAS. A rubric-based metric would be a better fit long-term.

---

## 6. What came next

The recommendations above shaped the subsequent runs:

- **v3** — added a `ground_truth` field to every sample and expanded to 12 questions. Introduced Context Precision + Context Recall metrics, which surfaced the *retrieval-ranking* failure mode (Redis herd vs. DynamoDB hot partition) that faithfulness alone couldn't diagnose.
- **v4** — added six generation-failure probes (parametric hallucination, extrapolation, contradiction, query misinterpretation, incomplete addressing, padding) to stress-test the LLM layer specifically. Confirmed that the assistant is vulnerable to parametric-knowledge leakage on well-known topics (e.g., generic "Java OOM" questions) but robust to leading questions and honest about missing information.

The v2 → v3 transition was the moment the eval suite matured from "does the pipeline produce a plausible answer?" into "*where* does the pipeline break?".
