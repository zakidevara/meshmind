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
```bash
curl ^"http://localhost:8080/api/ai/ask^" ^
  -H ^"Content-Type: text/plain^" ^
  --data-raw ^"have there been any issue causing from API Gateway 5xx recently?^"
```
