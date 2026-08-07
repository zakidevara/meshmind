import json
import time
from pathlib import Path

OUTPUT_PATH = Path(__file__).parent.parent / "data" / "slack_oncall_export.json"

# Curated Slack on-call threads. Each thread is a self-contained incident story:
# the root incident, ordered investigation replies that make sense for THAT incident,
# and a resolution or pending status that actually resolves the root cause.
# This produces a coherent dataset for RAG retrieval — a query about OOM will return
# messages about memory profiling and heap dumps, not about SQS parser bugs.
THREADS = [
    {
        "root": ("U1111A", "P1: ECS tasks for the main Spring Boot app are crash-looping with OOMKilled."),
        "replies": [
            ("U2222B", "Looking into Datadog now. CPU is normal but memory spiked right before the crash."),
            ("U1111A", "I pulled a heap dump from the last running task. Retained size on the response cache is 1.8GB — the LRU eviction isn't firing."),
            ("U3333C", "Confirmed: the cache TTL was accidentally set to Duration.ZERO in the last deploy, so entries never expire."),
        ],
        "resolution": ("U1111A", "Resolution: Reverted the cache TTL change and scaled ECS memory from 2GB to 4GB as a safety buffer. Stable now. Next action: Profile the heap dump tomorrow to right-size the memory limit."),
    },
    {
        "root": ("U2222B", "P2: Huge spike in the SQS Dead Letter Queue (DLQ) for payment processing."),
        "replies": [
            ("U4444D", "DLQ is at 12k messages. Sampling a few — they all fail JSON parsing on the `refund_reason` field."),
            ("U2222B", "The upstream team started sending `refund_reason: null` last night. Our consumer's Jackson config rejects nulls on that field."),
            ("U4444D", "Patched the consumer to treat null as empty string and redeployed."),
        ],
        "resolution": ("U2222B", "Mitigation: Flushed the SQS DLQ back to the main queue after fixing the parsing bug in the consumer. DLQ is draining at ~200/s."),
    },
    {
        "root": ("U3333C", "P1: API Gateway is returning 502s for the checkout endpoint."),
        "replies": [
            ("U5555E", "Started ~15 min ago. Correlated with the API Gateway integration timeout config change we pushed at 14:02."),
            ("U3333C", "Reverted the timeout change back to 29s. Still seeing 502s."),
            ("U5555E", "The underlying Lambda is timing out at 30s now — the API Gateway revert didn't help because the Lambda itself was also changed."),
        ],
        "resolution": ("U3333C", "Pending: Reverted the API Gateway config, but we are still seeing 502s. Escalating to AWS support and rolling back the Lambda deployment as well."),
    },
    {
        "root": ("U4444D", "P3: HikariCP connection pool timeout exceptions in the user service."),
        "replies": [
            ("U1111A", "Pool size is 20, active connections 20 — pool is saturated. Checking slow query log."),
            ("U4444D", "Found it: the new `getUserSessions` query is doing a full table scan on `sessions` because we forgot the index on `user_id`."),
            ("U1111A", "Adding the index now with CONCURRENTLY so we don't lock the table."),
        ],
        "resolution": ("U4444D", "Resolution: Added btree index on sessions(user_id). Query latency dropped from 4s to 8ms. Pool utilization back to <20%."),
    },
    {
        "root": ("U5555E", "P2: Thundering herd effect observed on the cache cluster after the Redis restart."),
        "replies": [
            ("U2222B", "All services fired their cache-miss fallback simultaneously. DB CPU pegged at 100%."),
            ("U5555E", "Root cause is that our clients have zero jitter on cache reconnect — they all hit the DB in the same 200ms window."),
        ],
        "resolution": ("U5555E", "Resolution: Added exponential backoff with jitter (0-500ms) to our retry logic to prevent the thundering herd. DB CPU is dropping. Deployed to all services."),
    },
    {
        "root": ("U1111A", "P2: Lambda timeouts on the third-party webhook handler."),
        "replies": [
            ("U3333C", "The webhook forwards to Stripe's API. Stripe is showing degraded status on their status page."),
            ("U1111A", "Our Lambda timeout is 10s but Stripe is taking 12-14s to respond right now."),
        ],
        "resolution": ("U1111A", "Pending: Increased Lambda timeout to 15s as a band-aid. We need to make this handler async (SQS-backed) tomorrow so we don't block on Stripe."),
    },
    {
        "root": ("U2222B", "P3: S3 Access Denied errors when the new reporting cron tries to upload PDFs."),
        "replies": [
            ("U4444D", "The cron runs as the `reporting-task` ECS task role. Checking the attached IAM policy."),
            ("U2222B", "The IAM role for the ECS task is missing `s3:PutObject` on the `reports-prod` bucket. It only has `s3:GetObject`."),
        ],
        "resolution": ("U2222B", "Resolution: Attached the correct IAM policy granting s3:PutObject on arn:aws:s3:::reports-prod/*. Uploads are working."),
    },
    {
        "root": ("U3333C", "P2: Internal service-to-service TLS handshake failing with 'certificate expired'."),
        "replies": [
            ("U5555E", "The internal CA cert for the `orders` service expired at 00:00 UTC. Renewal cron didn't fire."),
            ("U3333C", "The renewal cron was tied to the old Jenkins job that we decommissioned last month. No one migrated it."),
        ],
        "resolution": ("U3333C", "Resolution: Manually rotated the cert via cert-manager and set up a new K8s CronJob for renewals. Added a Datadog monitor for cert expiry <14 days."),
    },
    {
        "root": ("U4444D", "P1: Post-deploy NullPointerException on the checkout confirmation page."),
        "replies": [
            ("U1111A", "The new PromotionService returns null when no promo applies, but the caller assumes an empty Optional."),
            ("U4444D", "Rolling back deployment v2.14.3 to v2.14.2 now."),
        ],
        "resolution": ("U4444D", "Resolution: Rolled back the deployment. Fix PR is up to make PromotionService return Optional.empty() instead of null. Will redeploy after review."),
    },
    {
        "root": ("U5555E", "P2: RDS primary failed over to a different AZ unexpectedly."),
        "replies": [
            ("U2222B", "AWS Health Dashboard shows scheduled maintenance in us-east-1a. Our primary was in that AZ."),
            ("U5555E", "Replica in us-east-1b was promoted. Seeing ~30s of connection errors during the flip."),
        ],
        "resolution": ("U5555E", "Resolution: Failover completed cleanly. Reviewed our HikariCP config — reduced `maxLifetime` to 30 min so connections rotate more often and pick up DNS changes faster."),
    },
    {
        "root": ("U1111A", "P2: Kafka consumer lag on the analytics pipeline growing at 5k msg/min."),
        "replies": [
            ("U3333C", "Consumer group rebalance is happening every 90s — one consumer keeps getting kicked."),
            ("U1111A", "The kicked consumer's session timeout is hitting because a downstream ClickHouse write is taking 45s."),
        ],
        "resolution": ("U1111A", "Resolution: Increased Kafka `session.timeout.ms` from 30s to 60s and added batch flushing to the ClickHouse writer. Lag draining at 8k/min."),
    },
    {
        "root": ("U2222B", "P3: Prometheus scrapes failing for all pods in the `payments` namespace."),
        "replies": [
            ("U4444D", "Prometheus can't reach the pod IPs. Testing from the Prometheus pod: connection refused."),
            ("U2222B", "New NetworkPolicy was applied to `payments` yesterday that only allows ingress from `frontend` namespace. Prometheus is in `monitoring`."),
        ],
        "resolution": ("U2222B", "Resolution: Updated the NetworkPolicy to also allow ingress from the `monitoring` namespace on the metrics port. Scrapes recovered."),
    },
    {
        "root": ("U3333C", "P2: Users seeing stale JavaScript bundle after the frontend deploy."),
        "replies": [
            ("U5555E", "CloudFront is serving the old `main.abc123.js` — cache TTL is set to 1 year on the bundle."),
            ("U3333C", "That's expected for hashed assets, but the `index.html` also has 1 year TTL, so browsers never fetch the new bundle name."),
        ],
        "resolution": ("U3333C", "Resolution: Set Cache-Control: no-cache on index.html in the CloudFront behavior. Invalidated /index.html. Users will pick up new bundles on next page load."),
    },
    {
        "root": ("U4444D", "P2: DynamoDB throttling on the `sessions` table, ~2% of reads failing."),
        "replies": [
            ("U1111A", "CloudWatch shows one partition getting 80% of the traffic. Classic hot partition."),
            ("U4444D", "The partition key is `tenant_id` — one enterprise customer accounts for 60% of active sessions."),
        ],
        "resolution": ("U4444D", "Pending: Adding a suffix (`tenant_id#shard_N` where N = hash(session_id) % 10) to spread writes. Migration will happen this weekend during the low-traffic window."),
    },
    {
        "root": ("U5555E", "P2: Nginx ingress returning 504 Gateway Timeout for /api/reports endpoint."),
        "replies": [
            ("U2222B", "The reports endpoint generates PDFs and can take 45s. Nginx ingress default timeout is 60s but I'm seeing 504s at exactly 30s."),
            ("U5555E", "Found it — the ingress has `nginx.ingress.kubernetes.io/proxy-read-timeout: \"30\"` in its annotations from a copy-paste from another service."),
        ],
        "resolution": ("U5555E", "Resolution: Bumped proxy-read-timeout to 90s for the /api/reports path. Long-term: move PDF generation to an async job queue so the HTTP request returns immediately."),
    },
    {
        "root": ("U1111A", "P3: JVM GC pauses of 3-5s observed on the recommendations service."),
        "replies": [
            ("U3333C", "G1GC logs show Full GC events triggered by humongous allocations. Something is allocating >12MB objects (region size is 24MB / 2)."),
            ("U1111A", "The new recommendation model loads a 40MB feature array into a single ByteBuffer on every request."),
        ],
        "resolution": ("U1111A", "Resolution: Moved the feature array to a shared static field (loaded once at startup). Full GC events dropped to zero. P99 latency down from 800ms to 60ms."),
    },
]

USERS_FALLBACK = ["U1111A", "U2222B", "U3333C", "U4444D", "U5555E"]


def generate_slack_data():
    messages = []
    base_ts = time.time() - (30 * 24 * 60 * 60)  # Start 30 days ago

    for i, thread in enumerate(THREADS):
        thread_ts = str(base_ts + (i * 3600))

        root_user, root_text = thread["root"]
        messages.append({
            "type": "message",
            "user": root_user,
            "text": f":siren: {root_text}",
            "ts": thread_ts,
            "thread_ts": thread_ts,
        })

        for j, (reply_user, reply_text) in enumerate(thread["replies"]):
            reply_ts = str(float(thread_ts) + ((j + 1) * 300))
            messages.append({
                "type": "message",
                "user": reply_user,
                "text": reply_text,
                "ts": reply_ts,
                "thread_ts": thread_ts,
            })

        final_user, final_text = thread["resolution"]
        final_ts = str(float(thread_ts) + 3600)
        messages.append({
            "type": "message",
            "user": final_user,
            "text": final_text,
            "ts": final_ts,
            "thread_ts": thread_ts,
        })

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(OUTPUT_PATH, "w") as f:
        json.dump({"messages": messages}, f, indent=2)

    print(f"Generated {len(THREADS)} threads ({len(messages)} total messages) in {OUTPUT_PATH}")


if __name__ == "__main__":
    generate_slack_data()
