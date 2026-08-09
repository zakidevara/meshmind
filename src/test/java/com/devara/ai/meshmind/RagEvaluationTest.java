package com.devara.ai.meshmind;

import com.devara.ai.meshmind.evaluation.RagEvaluationLogger;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs a fixed set of (question, ground_truth) prompts through the OnCallAssistant and dumps
 * each result to eval_samples.jsonl for later evaluation with eval/evaluate.py.
 *
 * <p>Opt-in via system property to avoid burning LLM quota on every `mvn test` run:
 * <pre>mvn test -Dtest=RagEvaluationTest -Drun.eval.test=true</pre>
 *
 * <p>Requires: Milvus + Ollama running, and the LLM provider (openai or gemini) configured.
 */
@Slf4j
@SpringBootTest
@EnabledIfSystemProperty(named = "run.eval.test", matches = "true")
class RagEvaluationTest {

    private static final Path OUTPUT_PATH = Path.of("eval", "input", "eval_samples.jsonl");

    /** Prompts spanning different threads in slack_oncall_export.json, plus a couple of edge cases. */
    private static final List<Case> CASES = List.of(
        // straightforward — should retrieve the ECS OOM thread
        new Case(
            "How did we fix the ECS task crash-looping with OOMKilled?",
            "The response cache TTL was accidentally set to Duration.ZERO in the last deploy, causing entries never to expire and retained size to grow to 1.8GB. The fix was to revert the TTL change and scale ECS memory from 2GB to 4GB as a safety buffer."
        ),
        // rephrasing test — semantic match without keyword overlap
        new Case(
            "Our Spring Boot service keeps getting killed by out-of-memory errors. Any recent similar incident and how was it resolved?",
            "The response cache TTL was misconfigured to Duration.ZERO, so the LRU eviction never fired and retained size hit 1.8GB. The team reverted the TTL and increased ECS memory from 2GB to 4GB."
        ),
        // SQS DLQ parser bug
        new Case(
            "There is a spike in SQS DLQ for payments. Have we seen this before and what caused it?",
            "The upstream team started sending refund_reason: null and the consumer's Jackson config rejected nulls on that field, causing JSON parse failures. The consumer was patched to treat null as empty string and the DLQ was replayed to the main queue."
        ),
        // API Gateway 502
        new Case(
            "The checkout endpoint is returning 502s. Is there any similar issue previously? What is the root cause and how to resolve it?",
            "A previous P1 incident had API Gateway returning 502s on /checkout after a gateway integration timeout config change. The underlying Lambda was also changed in that deploy and was timing out at 30s. Reverting the API Gateway config alone did not help; the resolution required rolling back the Lambda deployment as well, with escalation to AWS support."
        ),
        // Hikari pool
        new Case(
            "Users service is throwing HikariCP connection pool timeout exceptions. What should I check?",
            "In a past incident the pool was saturated because the new getUserSessions query was doing a full table scan on the sessions table due to a missing index on user_id. Adding a btree index on sessions(user_id) with CONCURRENTLY dropped query latency from 4s to 8ms and returned pool utilization to under 20%."
        ),
        // Redis thundering herd — rephrased as "sudden DB CPU spike"
        new Case(
            "There is a sudden surge of traffic to our DB causing 100% CPU. What could be the reason?",
            "A thundering herd occurred after a Redis restart: all services fired their cache-miss fallback in the same 200ms window because clients had zero jitter on cache reconnect. The fix was to add exponential backoff with jitter (0-500ms) to the retry logic, which was deployed to all services."
        ),
        // Nginx 504
        new Case(
            "Why is our /api/reports endpoint returning 504 Gateway Timeout?",
            "The nginx ingress had `nginx.ingress.kubernetes.io/proxy-read-timeout: \"30\"` in its annotations copy-pasted from another service, so requests timed out at 30s even though PDF generation takes up to 45s. The fix was to bump proxy-read-timeout to 90s for the /api/reports path, with a longer-term plan to move PDF generation to an async job queue."
        ),
        // S3 IAM
        new Case(
            "The reporting cron is getting AccessDenied when uploading PDFs to S3. What went wrong?",
            "The `reporting-task` ECS task role had s3:GetObject on the reports-prod bucket but was missing s3:PutObject. The fix was to attach a policy granting s3:PutObject on arn:aws:s3:::reports-prod/*."
        ),
        // Cert expiry
        new Case(
            "Internal service-to-service calls started failing with certificate expired. How was this fixed last time?",
            "The internal CA cert for the orders service expired because its renewal cron was tied to an old Jenkins job that had been decommissioned. The cert was manually rotated via cert-manager and a new Kubernetes CronJob was created for renewals, along with a Datadog monitor for certs expiring within 14 days."
        ),
        // NPE rollback
        new Case(
            "Post-deploy NullPointerException on the checkout confirmation page. What was the cause and how was it fixed?",
            "The new PromotionService returned null when no promo applied, but the caller assumed an Optional. The deploy was rolled back from v2.14.3 to v2.14.2 while a fix PR to make PromotionService return Optional.empty() was reviewed."
        ),
        // JVM GC
        new Case(
            "The recommendations service is experiencing multi-second JVM GC pauses. What could cause it?",
            "The new recommendation model was loading a 40MB feature array into a single ByteBuffer on every request, triggering G1GC Full GC events due to humongous allocations. Moving the feature array to a shared static field loaded once at startup eliminated the Full GC events and reduced p99 latency from 800ms to 60ms."
        ),
        // Fallback trigger — no relevant thread exists for this
        new Case(
            "How do I configure Prometheus federation across three clusters?",
            "There is no internal incident or documentation about Prometheus federation across clusters, so the assistant should reply that it cannot find this information."
        )
    );

    @Autowired
    private OnCallAssistant onCallAssistant;

    @Autowired
    private RagEvaluationLogger evaluationLogger;

    @Test
    void generateEvalSamples() throws Exception {
        Files.deleteIfExists(OUTPUT_PATH);
        log.info("Running {} eval cases; writing to {}", CASES.size(), OUTPUT_PATH.toAbsolutePath());

        for (int i = 0; i < CASES.size(); i++) {
            Case c = CASES.get(i);
            log.info("[{}/{}] {}", i + 1, CASES.size(), c.question());
            String answer = onCallAssistant.ask(c.question());
            evaluationLogger.log(c.question(), answer, c.groundTruth());
        }

        log.info("Done. Run `eval\\.venv\\Scripts\\python.exe eval\\evaluate.py` to score.");
    }

    private record Case(String question, String groundTruth) {}
}
