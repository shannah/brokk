Brokk runs through litellm now but we don't have litellm-as-a-service up and running yet.

So here's how you run litellm locally:

1. docker pull ghcr.io/berriai/litellm:main-latest
2. docker run -v $(pwd)/litellm_config.yaml:/app/config.yaml -e OPENAI_API_KEY=`cat ~/.secrets/openai_api_key` -e ANTHROPIC_API_KEY=`cat ~/.secrets/anthropic_api_key` -e DEEPSEEK_API_KEY=`cat ~/.secrets/deepseek_api_key` -e GEMINI_API_KEY=`cat ~/.secrets/gemini_api_key` -e GROK_API_KEY=`cat ~/.secrets/grok_api_key` -p 4000:4000 ghcr.io/berriai/litellm:main-latest --config /app/config.yaml

Modify the above to point to secrets on disk, or just hardcode them if you prefer.

Brokk will load the models from litellm so you can run with fewer models than this.
