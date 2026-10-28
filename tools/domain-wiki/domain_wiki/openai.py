import hashlib
import json
import urllib.error
import urllib.request


class OpenAIError(ValueError):
    pass


class UrlLibTransport:
    def request(self, method, url, headers, body=None):
        request = urllib.request.Request(url, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8", errors="replace")
            try:
                payload = json.loads(raw)
            except json.JSONDecodeError:
                payload = {"error": {"message": raw}}
            return error.code, payload


class OpenAIClient:
    def __init__(
        self,
        api_key,
        *,
        model="gpt-6-luna",
        reasoning_effort="medium",
        max_input_characters=60_000,
        max_output_tokens=8_000,
        prompt_cache_mode="off",
        prompt_cache_ttl="30m",
        prompt_cache_namespace="salmonbus-domain-wiki",
        transport=None,
    ):
        if not api_key:
            raise OpenAIError("OPENAI_API_KEY is required")
        self.api_key = api_key
        self.model = model
        self.reasoning_effort = reasoning_effort
        self.max_input_characters = max_input_characters
        self.max_output_tokens = max_output_tokens
        if prompt_cache_mode not in {"off", "explicit"}:
            raise OpenAIError("prompt_cache_mode must be off or explicit")
        if prompt_cache_ttl not in {"30m", "12h"}:
            raise OpenAIError("prompt_cache_ttl must be 30m or 12h")
        self.prompt_cache_mode = prompt_cache_mode
        self.prompt_cache_ttl = prompt_cache_ttl
        self.prompt_cache_namespace = prompt_cache_namespace
        self.transport = transport or UrlLibTransport()

    def review(self, prompt_payload, schema):
        instructions = prompt_payload["systemInstructions"]
        dynamic_payload = {
            key: value
            for key, value in prompt_payload.items()
            if key != "systemInstructions"
        }
        serialized = json.dumps(
            dynamic_payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        input_characters = len(instructions) + len(serialized)
        if input_characters > self.max_input_characters:
            raise OpenAIError(
                "model input is too large: "
                f"{input_characters} > {self.max_input_characters} characters"
            )
        response_schema = {
            key: value
            for key, value in schema.items()
            if key not in {"$schema", "$id", "title"}
        }
        developer_content = {"type": "input_text", "text": instructions}
        cache_enabled = self.prompt_cache_mode == "explicit" and len(instructions) >= 4_096
        if cache_enabled:
            developer_content["prompt_cache_breakpoint"] = {
                "mode": "explicit",
                "ttl": self.prompt_cache_ttl,
            }
        request_body = {
            "model": self.model,
            "input": [
                {
                    "role": "developer",
                    "content": [developer_content],
                },
                {"role": "user", "content": serialized},
            ],
            "reasoning": {"effort": self.reasoning_effort},
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "salmonbus_policy_review",
                    "strict": True,
                    "schema": response_schema,
                }
            },
            "max_output_tokens": self.max_output_tokens,
            "store": False,
        }
        if cache_enabled:
            request_body["prompt_cache_key"] = self._prompt_cache_key(
                prompt_payload,
                response_schema,
            )
            request_body["prompt_cache_options"] = {"mode": "explicit"}
        status, response = self.transport.request(
            "POST",
            "https://api.openai.com/v1/responses",
            {
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            json.dumps(request_body, ensure_ascii=False).encode("utf-8"),
        )
        if status < 200 or status >= 300:
            message = response.get("error", {}).get("message", "unknown error")
            raise OpenAIError(f"OpenAI API {status}: {message}")
        if response.get("status") == "incomplete":
            reason = response.get("incomplete_details", {}).get("reason", "unknown")
            raise OpenAIError(f"OpenAI response incomplete: {reason}")
        output_text = _output_text(response)
        try:
            review = json.loads(output_text)
        except json.JSONDecodeError as error:
            raise OpenAIError("OpenAI response is not valid JSON") from error
        if not isinstance(review, dict):
            raise OpenAIError("OpenAI review must be a JSON object")
        usage = response.get("usage", {})
        input_details = usage.get("input_tokens_details", {})
        write_details = input_details.get("cache_write_tokens_details", {})
        return review, {
            "inputTokens": usage.get("input_tokens"),
            "outputTokens": usage.get("output_tokens"),
            "cachedInputTokens": input_details.get("cached_tokens", 0),
            "cacheWriteInputTokens": input_details.get("cache_write_tokens", 0),
            "cacheWrite30mInputTokens": write_details.get("ttl_30m_tokens", 0),
            "cacheWrite12hInputTokens": write_details.get("ttl_12h_tokens", 0),
        }

    def _prompt_cache_key(self, prompt_payload, response_schema):
        identity = json.dumps(
            {
                "namespace": self.prompt_cache_namespace,
                "model": self.model,
                "promptVersion": prompt_payload.get("promptVersion"),
                "schema": response_schema,
            },
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        digest = hashlib.sha256(identity).hexdigest()[:24]
        return f"{self.prompt_cache_namespace}:{digest}"


def _output_text(response):
    texts = []
    for output in response.get("output", []):
        if not isinstance(output, dict):
            continue
        for content in output.get("content", []):
            if isinstance(content, dict) and content.get("type") == "output_text":
                text = content.get("text")
                if isinstance(text, str):
                    texts.append(text)
    if not texts:
        raise OpenAIError("OpenAI response has no output_text")
    return "".join(texts)
