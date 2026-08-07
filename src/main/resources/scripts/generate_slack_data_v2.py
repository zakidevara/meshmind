import json
import time
from pathlib import Path

OUTPUT_PATH = Path(__file__).parent.parent / "data" / "slack_oncall_export.json"

# Curated on-call threads written to feel like real Slack conversations:
# typos, tags, short pings ("any update?"), corrections ("nvm"), casual tone,
# and not every thread wraps up neatly. The resolution (when present) is always
# the last message in the thread so retrieval still surfaces the outcome.
# Each thread is: [(user, text), ...]. First message is the root (with :siren:
# prefix when appropriate), rest are replies in chronological order.
THREADS = [
    # OOMKilled ECS
    [
        ("U1111A", ":siren: ecs tasks OOMKilling again on the main spring boot app"),
        ("U2222B", "<@U1111A> same as last week's cache thing?"),
        ("U1111A", "checking datadog, mem spike right before crash, cpu normal"),
        ("U2222B", "heap dump?"),
        ("U1111A", "pulling now"),
        ("U3333C", "any update on this? getting alerts"),
        ("U1111A", "yeah retained size on response cache is 1.8gb, LRU not firing"),
        ("U3333C", "wait didnt <@U4444D> change the ttl yesterday??"),
        ("U4444D", "ughhh yes. TTL got set to Duration.ZERO in the deploy, entries never expire. my bad"),
        ("U1111A", "reverting the ttl + bumping ecs mem 2->4gb as buffer. stable now. will profile the heap dump tmr to right-size the memory limit"),
    ],
    # SQS DLQ parser bug
    [
        ("U2222B", ":siren: huge spike in the SQS DLQ for payment processing"),
        ("U4444D", "on it"),
        ("U4444D", "DLQ at 12k, sampled a few, all fail json parsing on `refund_reason`"),
        ("U2222B", "the payments team pushed something last night?"),
        ("U4444D", "yep they started sending refund_reason: null. our jackson config rejects nulls on that field"),
        ("U2222B", "<@U4444D> can u patch consumer to treat null as empty string?"),
        ("U4444D", "already on it"),
        ("U3333C", "may i know status of this? the finance dashboard is showing 0 refunds today"),
        ("U4444D", "patched + redeployed, replaying DLQ back to main queue now, draining at ~200/s"),
    ],
    # API Gateway 502
    [
        ("U3333C", ":siren: P1 API Gateway returning 502s for /checkout"),
        ("U5555E", "started ~15 min ago. correlates with the gateway integration timeout change we pushed at 14:02"),
        ("U3333C", "reverting timeout back to 29s"),
        ("U3333C", "hmm still 502s after revert"),
        ("U5555E", "wait the lambda itself was also changed in that deploy. its timing out at 30s"),
        ("U3333C", "fk. rolling back the lambda deploy too"),
        ("U1111A", "escalated to AWS support just in case"),
        ("U3333C", "still pending, reverted the API Gateway config but 502s continue. rolling back lambda and waiting on AWS. will update"),
    ],
    # Hikari pool exhaustion
    [
        ("U4444D", ":siren: HikariCP timeouts in the user service, pool exhausted"),
        ("U1111A", "pool size 20, all 20 active. checking slow query log"),
        ("U4444D", "<@U1111A> :eyes:"),
        ("U1111A", "found it, new `getUserSessions` query is doing full table scan on sessions bc we forgot the index on user_id"),
        ("U4444D", "lol classic. adding it now with CONCURRENTLY so we dont lock the table"),
        ("U1111A", "added btree index on sessions(user_id). query latency 4s -> 8ms. pool utilization back under 20%"),
    ],
    # Redis thundering herd
    [
        ("U5555E", ":siren: thundering herd on cache cluster after redis restart, DB CPU at 100%"),
        ("U2222B", "all services fired cache-miss fallback simultaneously"),
        ("U2222B", "same 200ms window, we have no jitter on reconnect"),
        ("U3333C", "u guys need help?"),
        ("U5555E", "im ok, patching retry logic with jitter now"),
        ("U5555E", "added exponential backoff with jitter (0-500ms) to the retry logic to prevent the thundering herd. DB CPU dropping. deployed everywhere"),
    ],
    # Lambda webhook timeout
    [
        ("U1111A", ":siren: lambda timeouts on the third-party webhook handler"),
        ("U3333C", "stripe?"),
        ("U1111A", "yeah stripe. their status page shows degraded"),
        ("U3333C", "our lambda timeout is 10s but stripe is taking 12-14s rn"),
        ("U1111A", "band-aid: bumped lambda timeout to 15s. need to make this handler async (sqs-backed) tmr so we dont block on stripe"),
    ],
    # S3 IAM
    [
        ("U2222B", ":siren: reporting cron getting S3 AccessDenied on PDF uploads"),
        ("U4444D", "which task role? reporting-task?"),
        ("U2222B", "yeah"),
        ("U4444D", "role only has s3:GetObject on the reports-prod bucket, missing PutObject"),
        ("U2222B", "<@U4444D> can u add the policy?"),
        ("U4444D", "yep"),
        ("U4444D", "attached policy granting s3:PutObject on arn:aws:s3:::reports-prod/*. uploads working"),
    ],
    # Cert expiry
    [
        ("U3333C", ":siren: internal svc-to-svc TLS handshake failing, cert expired"),
        ("U5555E", "which svc?"),
        ("U3333C", "orders. CA cert expired at 00:00 UTC"),
        ("U5555E", "wtf we have a renewal cron"),
        ("U3333C", "it was on the old jenkins job we decomissioned last month. no one migrated it"),
        ("U5555E", ":facepalm:"),
        ("U3333C", "manually rotated the cert via cert-manager + set up a new k8s cronjob for renewals. added datadog monitor for cert expiry < 14 days so this doesnt happen again"),
    ],
    # NPE rollback
    [
        ("U4444D", ":siren: NPE on the checkout confirmation page, post-deploy"),
        ("U1111A", "which deploy?"),
        ("U4444D", "v2.14.3 that went out 20 min ago"),
        ("U1111A", "looking"),
        ("U1111A", "PromotionService returns null when no promo applies but caller assumes Optional"),
        ("U4444D", "rolling back to v2.14.2 now"),
        ("U2222B", "any status update?"),
        ("U4444D", "rolled back. fix PR is up to make PromotionService return Optional.empty() instead of null. will redeploy after review"),
    ],
    # RDS failover
    [
        ("U5555E", ":siren: RDS primary failed over to a different AZ, saw ~30s of connection errors"),
        ("U2222B", "aws maintenance? checking health dashboard"),
        ("U2222B", "yep scheduled maintenance in us-east-1a. our primary was there"),
        ("U5555E", "replica in us-east-1b was promoted. connections recovering"),
        ("U3333C", "we good?"),
        ("U5555E", "yeah failover completed cleanly. reviewed hikari config and reduced maxLifetime to 30 min so connections rotate more often and pick up DNS changes faster"),
    ],
    # Kafka consumer lag
    [
        ("U1111A", ":siren: kafka consumer lag on the analytics pipeline growing at 5k msg/min"),
        ("U3333C", "which consumer group?"),
        ("U1111A", "analytics-events-v2"),
        ("U3333C", "rebalance happening every 90s, one consumer keeps getting kicked"),
        ("U1111A", "the kicked one's session timeout is hitting bc a downstream clickhouse write is taking 45s"),
        ("U3333C", "wdyt, bump session.timeout.ms?"),
        ("U1111A", "yeah + add batch flushing on the clickhouse writer side"),
        ("U1111A", "bumped session.timeout.ms 30s -> 60s and added batch flushing to the clickhouse writer. lag draining at 8k/min"),
    ],
    # Prometheus NetworkPolicy
    [
        ("U2222B", ":siren: prometheus scrapes failing for all pods in payments namespace"),
        ("U4444D", "connection refused from prom pod when i test"),
        ("U2222B", "new NetworkPolicy applied to payments yesterday, only allows ingress from frontend ns. prometheus is in monitoring"),
        ("U4444D", "who added the policy without whitelisting monitoring... lol"),
        ("U2222B", "updated the NetworkPolicy to also allow ingress from monitoring ns on the metrics port. scrapes recovered"),
    ],
    # CloudFront cache
    [
        ("U3333C", ":siren: users seeing stale JS bundle after the frontend deploy"),
        ("U5555E", "cloudfront serving old main.abc123.js. TTL is 1yr on the bundle"),
        ("U3333C", "thats expected for hashed assets. issue is the index.html also has 1yr TTL so browsers never fetch the new bundle name"),
        ("U5555E", "oh"),
        ("U3333C", "set Cache-Control: no-cache on index.html in the cloudfront behavior + invalidated /index.html. users will pick up new bundles on next page load"),
    ],
    # DynamoDB throttling
    [
        ("U4444D", ":siren: dynamo throttling on the sessions table, ~2% reads failing"),
        ("U1111A", "cloudwatch shows one partition getting 80% of traffic. hot partition"),
        ("U4444D", "partition key is tenant_id. one enterprise customer accounts for 60% of active sessions"),
        ("U1111A", "may i know what the plan is here?"),
        ("U4444D", "adding a suffix (tenant_id#shard_N where N = hash(session_id) % 10) to spread writes. migration this weekend during low-traffic window. still pending"),
    ],
    # Nginx ingress 504
    [
        ("U5555E", ":siren: nginx ingress returning 504 for /api/reports"),
        ("U2222B", "reports generates PDFs, can take 45s. nginx default is 60s but im seeing 504 at exactly 30s"),
        ("U5555E", "checking annotations"),
        ("U5555E", "found it, the ingress has `nginx.ingress.kubernetes.io/proxy-read-timeout: \"30\"` in the annotations, copy-pasted from another svc"),
        ("U2222B", "damn"),
        ("U5555E", "bumped proxy-read-timeout to 90s for /api/reports path. long-term: moving PDF generation to an async job queue so the HTTP request returns immediately"),
    ],
    # JVM Full GC
    [
        ("U1111A", ":siren: JVM GC pauses of 3-5s on the recommendations service"),
        ("U3333C", "G1GC full GC?"),
        ("U1111A", "yeah, humongous allocations. something is allocating >12MB objects (region size 24MB / 2)"),
        ("U3333C", "the new rec model?"),
        ("U1111A", "yeah loads a 40MB feature array into a single ByteBuffer on every request :facepalm:"),
        ("U1111A", "moved the feature array to a shared static field (loaded once at startup). full GC events dropped to zero. p99 latency 800ms -> 60ms"),
    ],
    # Extra thread: someone pinging with no resolution (realistic — some threads trail off)
    [
        ("U2222B", ":siren: seeing intermittent 500s on /api/users/me, maybe 1 in 50"),
        ("U4444D", "<@U2222B> got a request id?"),
        ("U2222B", "req_9f3a2c... trying to correlate in datadog"),
        ("U4444D", "nothing obvious in the traces"),
        ("U3333C", "im also seeing it on staging"),
        ("U2222B", "afk for lunch, will pick this up in an hour"),
        ("U4444D", "any update on this?"),
        ("U2222B", "back. still cant repro consistently. leaving datadog rum session recording on and will check tmr"),
    ],
]


def generate_slack_data():
    messages = []
    base_ts = time.time() - (30 * 24 * 60 * 60)  # Start 30 days ago

    for i, thread in enumerate(THREADS):
        thread_ts = str(base_ts + (i * 3600))

        for j, (user, text) in enumerate(thread):
            msg_ts = thread_ts if j == 0 else str(float(thread_ts) + (j * 300))
            messages.append({
                "type": "message",
                "user": user,
                "text": text,
                "ts": msg_ts,
                "thread_ts": thread_ts,
            })

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(OUTPUT_PATH, "w") as f:
        json.dump({"messages": messages}, f, indent=2)

    print(f"Generated {len(THREADS)} threads ({len(messages)} total messages) in {OUTPUT_PATH}")


if __name__ == "__main__":
    generate_slack_data()
