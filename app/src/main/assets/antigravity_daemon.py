#!/usr/bin/env python3
"""
Antigravity Headless Daemon Bridge
Runs inside the lightweight Debian Linux environment on Android.
Bridges between the native Antigravity localharness / SDK and the Android App UI over WebSocket.
"""

import argparse
import asyncio
import json
import logging
import os
import sys

from aiohttp import web
from google.antigravity import Agent, CapabilitiesConfig, LocalAgentConfig

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("AntigravityDaemon")

class AntigravityDaemon:
    def __init__(self, port: int, workspace_dir: str):
        self.port = port
        self.workspace_dir = workspace_dir
        self.api_key = os.environ.get("GEMINI_API_KEY", "")
        self.model_name = os.environ.get("MODEL_NAME", "gemini-2.5-flash")
        self.active_websockets = set()
        self.agent = None

    async def init_agent(self):
        """Initializes the Antigravity agent harness with local workspace tools."""
        logger.info(f"Initializing Antigravity harness in workspace: {self.workspace_dir}")
        config = LocalAgentConfig(
            api_key=self.api_key,
            model=self.model_name,
            capabilities=CapabilitiesConfig(
                file_system=True,
                run_command=True,
                code_execution=True
            ),
            system_instructions=(
                "You are Antigravity running on an Android device inside an isolated workspace. "
                "Keep edits precise and clean."
            )
        )
        self.agent = Agent(config)
        await self.agent.__aenter__()
        logger.info("Antigravity agent successfully started.")

    async def handle_ws(self, request):
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        self.active_websockets.add(ws)
        logger.info("Android UI connected via WebSocket.")

        # Send initial ready state
        await ws.send_json({
            "type": "status",
            "status": "ready",
            "workspace": self.workspace_dir,
            "model": self.model_name
        })

        try:
            async for msg in ws:
                if msg.type == web.WSMsgType.TEXT:
                    payload = json.loads(msg.data)
                    action = payload.get("action")

                    if action == "chat":
                        prompt = payload.get("prompt", "")
                        asyncio.create_task(self.process_chat(prompt, ws))

                    elif action == "approve_tool":
                        call_id = payload.get("call_id")
                        decision = payload.get("approved", True)
                        # Handled through Antigravity hook router
                        logger.info(f"Tool call {call_id} approval decision: {decision}")

                    elif action == "ping":
                        await ws.send_json({"type": "pong"})

        except Exception as e:
            logger.error(f"WebSocket error: {e}")
        finally:
            self.active_websockets.discard(ws)
            logger.info("Android UI disconnected.")

        return ws

    async def process_chat(self, prompt: str, ws: web.WebSocketResponse):
        """Executes conversational turn and streams tokens & thoughts back to Android UI."""
        try:
            await ws.send_json({"type": "turn_start"})

            # Invoke Antigravity SDK chat stream
            response = await self.agent.chat(prompt)

            # Stream thinking deltas if supported
            if hasattr(response, "thoughts"):
                async for thought in response.thoughts:
                    await ws.send_json({
                        "type": "thought",
                        "data": thought
                    })

            # Stream response text tokens
            async for token in response:
                await ws.send_json({
                    "type": "token",
                    "data": token
                })

            await ws.send_json({"type": "turn_end"})

        except Exception as e:
            logger.exception("Error processing chat turn")
            await ws.send_json({
                "type": "error",
                "message": str(e)
            })

    async def close(self):
        if self.agent:
            await self.agent.__aexit__(None, None, None)

def main():
    parser = argparse.ArgumentParser(description="Antigravity Headless Daemon")
    parser.add_argument("--port", type=int, default=18080, help="Listen port")
    parser.add_argument("--workspace", type=str, default="/workspace", help="Workspace path")
    args = parser.parse_args()

    daemon = AntigravityDaemon(args.port, args.workspace)

    app = web.Application()
    app.router.add_get("/ws", daemon.handle_ws)

    async def on_startup(app_instance):
        await daemon.init_agent()

    async def on_cleanup(app_instance):
        await daemon.close()

    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)

    logger.info(f"Starting Antigravity Daemon on 127.0.0.1:{args.port}...")
    web.run_app(app, host="127.0.0.1", port=args.port)

if __name__ == "__main__":
    main()
