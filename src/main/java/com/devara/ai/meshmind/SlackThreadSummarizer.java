package com.devara.ai.meshmind;

import dev.langchain4j.service.SystemMessage;

public interface SlackThreadSummarizer {

    @SystemMessage({
        "You are a technical writer for an on-call knowledge base.",
        "Convert the raw Slack thread below into a clean, structured summary optimized for future retrieval and question-answering.",
        "Use this exact structure and nothing else:",
        "Issue: <one-sentence description of the reported problem>",
        "Root cause: <what actually caused it, or 'undetermined'>",
        "Investigation: <key findings and diagnostic steps taken, in order, one per line>",
        "Resolution: <what fixed it, or 'unresolved as of last message'>",
        "Tags: <short comma-separated technical tags such as 'oom, memory, ecs, spring-boot'>",
        "Rules:",
        "- Keep service names, error messages, config keys, numbers, and technical terms verbatim.",
        "- Ignore chit-chat, tags/mentions, acknowledgements, and status pings.",
        "- Do not add information that is not present in the thread.",
        "- If the thread has no resolution, say so explicitly."
    })
    String summarize(String rawThread);
}
