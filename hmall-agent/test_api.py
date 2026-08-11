#!/usr/bin/env python3
"""调用本地 vLLM OpenAI 兼容接口的简单聊天客户端。"""

from __future__ import annotations

import argparse
import os
import sys

from openai import OpenAI


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Qwen3.5-4B vLLM 聊天客户端")
    parser.add_argument(
        "--base-url",
        default=os.getenv("OPENAI_BASE_URL", "http://127.0.0.1:18000/v1"),
        help="API 地址，默认 http://127.0.0.1:8000/v1",
    )
    parser.add_argument(
        "--api-key",
        default=os.getenv("OPENAI_API_KEY", "EMPTY"),
        help="API Key（本地服务可填 EMPTY）",
    )
    parser.add_argument(
        "--model",
        default=os.getenv("VLLM_MODEL", "Qwen3.5-4B"),
        help="服务端模型名（与 --served-model-name 一致）",
    )
    parser.add_argument(
        "--prompt",
        default=None,
        help="单次提问；不传则进入交互模式",
    )
    parser.add_argument(
        "--no-think",
        action="store_true",
        help="关闭 thinking，直接输出回答",
    )
    parser.add_argument(
        "--max-tokens",
        type=int,
        default=2048,
        help="最大生成 token 数",
    )
    parser.add_argument(
        "--temperature",
        type=float,
        default=0.7,
        help="采样温度",
    )
    parser.add_argument(
        "--stream",
        action="store_true",
        help="流式输出",
    )
    return parser.parse_args()


def build_extra_body(no_think: bool) -> dict | None:
    if not no_think:
        return None
    return {"chat_template_kwargs": {"enable_thinking": False}}


def chat_once(
    client: OpenAI,
    model: str,
    messages: list[dict],
    *,
    max_tokens: int,
    temperature: float,
    stream: bool,
    extra_body: dict | None,
) -> str:
    kwargs = {
        "model": model,
        "messages": messages,
        "max_tokens": max_tokens,
        "temperature": temperature,
        "stream": stream,
    }
    if extra_body is not None:
        kwargs["extra_body"] = extra_body

    if not stream:
        resp = client.chat.completions.create(**kwargs)
        content = resp.choices[0].message.content or ""
        print(content)
        return content

    parts: list[str] = []
    with client.chat.completions.create(**kwargs) as resp:
        for chunk in resp:
            delta = chunk.choices[0].delta.content
            if delta:
                parts.append(delta)
                print(delta, end="", flush=True)
    print()
    return "".join(parts)


def interactive_loop(
    client: OpenAI,
    model: str,
    *,
    max_tokens: int,
    temperature: float,
    stream: bool,
    extra_body: dict | None,
) -> None:
    messages: list[dict] = []
    print(f"已连接 {client.base_url} | model={model}")
    print("输入内容回车发送；输入 /exit 或 /quit 退出；输入 /clear 清空上下文。\n")

    while True:
        try:
            user = input("You> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\n再见。")
            break

        if not user:
            continue
        if user in {"/exit", "/quit"}:
            print("再见。")
            break
        if user == "/clear":
            messages.clear()
            print("上下文已清空。")
            continue

        messages.append({"role": "user", "content": user})
        print("Assistant> ", end="", flush=True)
        try:
            reply = chat_once(
                client,
                model,
                messages,
                max_tokens=max_tokens,
                temperature=temperature,
                stream=stream,
                extra_body=extra_body,
            )
        except Exception as exc:  # noqa: BLE001
            messages.pop()
            print(f"\n请求失败: {exc}", file=sys.stderr)
            continue

        messages.append({"role": "assistant", "content": reply})


def main() -> None:
    args = parse_args()
    client = OpenAI(base_url=args.base_url, api_key=args.api_key)
    extra_body = build_extra_body(args.no_think)

    if args.prompt is not None:
        chat_once(
            client,
            args.model,
            [{"role": "user", "content": args.prompt}],
            max_tokens=args.max_tokens,
            temperature=args.temperature,
            stream=args.stream,
            extra_body=extra_body,
        )
        return

    interactive_loop(
        client,
        args.model,
        max_tokens=args.max_tokens,
        temperature=args.temperature,
        stream=args.stream,
        extra_body=extra_body,
    )


if __name__ == "__main__":
    main()
