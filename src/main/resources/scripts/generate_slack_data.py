import json
import random
import time

def generate_slack_data(num_threads=100):
    users = ["U1111A", "U2222B", "U3333C", "U4444D", "U5555E"]

    issues = [
        "P1: ECS tasks for the main Spring Boot app are crash-looping with OOMKilled.",
        "P2: Huge spike in the SQS Dead Letter Queue (DLQ) for payment processing.",
        "P1: API Gateway is returning 502s for the checkout endpoint.",
        "P3: HikariCP connection pool timeout exceptions in the user service.",
        "P2: Thundering herd effect observed on the cache cluster after the Redis restart.",
        "P2: Lambda timeouts on the third-party webhook handler.",
        "P3: S3 Access Denied errors when the new reporting cron tries to upload PDFs."
    ]

    investigations = [
        "Looking into Datadog now. CPU is normal but memory spiked right before the crash.",
        "I'm checking CloudWatch logs. Looks like we hit a connection limit.",
        "Rolling back the last deployment to see if it stabilizes.",
        "I can reproduce this locally. The query is doing a full table scan.",
        "Checking AWS Health Dashboard. No known outages in our region.",
        "The IAM role for the ECS task seems to be missing the `s3:PutObject` permission."
    ]

    resolutions = [
        {"text": "Resolution: Scaled up the ECS memory limits from 2GB to 4GB. Stable now. Next action: Profile the heap dump tomorrow.", "resolved": True},
        {"text": "Mitigation: Flushed the SQS DLQ back to the main queue after fixing the parsing bug in the consumer.", "resolved": True},
        {"text": "Resolution: Added jitter to our retry logic to prevent the thundering herd. DB CPU is dropping.", "resolved": True},
        {"text": "Resolution: Attached the correct IAM policy to the task role. S3 uploads are working.", "resolved": True},
        {"text": "Pending: Reverted the API Gateway config, but we are still seeing 502s. Escalating to AWS support.", "resolved": False},
        {"text": "Pending: Increased Lambda timeout to 15s as a band-aid. We need to optimize this downstream API call tomorrow.", "resolved": False},
        {"text": "Pending: Still investigating the Hikari pool exhaustion. DB locks might be the root cause.", "resolved": False}
    ]

    threads = []
    base_ts = time.time() - (30 * 24 * 60 * 60) # Start 30 days ago

    for i in range(num_threads):
        thread_ts = str(base_ts + (i * 3600))
        issue = random.choice(issues)

        # Root message
        thread = [
            {
                "type": "message",
                "user": random.choice(users),
                "text": f":siren: {issue}",
                "ts": thread_ts,
                "thread_ts": thread_ts
            }
        ]

        # Add 1 to 3 investigation replies
        reply_count = random.randint(1, 3)
        for j in range(reply_count):
            reply_ts = str(float(thread_ts) + ((j + 1) * 300))
            thread.append({
                "type": "message",
                "user": random.choice(users),
                "text": random.choice(investigations),
                "ts": reply_ts,
                "thread_ts": thread_ts
            })

        # Add resolution or pending state
        outcome = random.choice(resolutions)
        final_ts = str(float(thread_ts) + 3600)
        thread.append({
            "type": "message",
            "user": random.choice(users),
            "text": outcome["text"],
            "ts": final_ts,
            "thread_ts": thread_ts
        })

        threads.append(thread)

    # Flatten list for Slack API structure, but keep them grouped by thread_ts
    messages = [msg for thread in threads for msg in thread]

    with open("slack_oncall_export.json", "w") as f:
        json.dump({"messages": messages}, f, indent=2)

    print(f"Generated 100 threads ({len(messages)} total messages) in slack_oncall_export.json")

if __name__ == "__main__":
    generate_slack_data()