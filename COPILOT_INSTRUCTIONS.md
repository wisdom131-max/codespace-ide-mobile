# Copilot Instructions for this Android IDE terminal project

## Vision
Turn this app into an AI-native mobile IDE terminal experience inspired by the best ideas from Claude Code, Codex-style agents, terminal assistants, and modern developer tools. The goal is not to copy one product exactly, but to create a polished, minimal, powerful terminal workspace that feels modern and intelligent.

## Core design principle
Keep the existing app UI and layout intact. Do not redesign the app. Add features in a surgical, additive way. Prefer subtle controls such as small toggles, buttons, settings rows, floating actions, or compact panels.

## Visual style
Aim for a clean, polished, Base44-like feel: minimal, elegant, fast, and premium. The interface should feel lightweight and modern, with thoughtful spacing, strong contrast, and restrained visual additions.

## What to build
The app should gradually evolve into a terminal-first AI coding environment with:
- zsh + Oh My Zsh auto-setup
- zsh-syntax-highlighting and zsh-autosuggestions
- session tabs with rename support
- text expansion/snippets
- SSH manager
- built-in file manager
- customizable toolbar buttons
- terminal color themes
- backup and restore
- AI-assisted shell workflows
- model integration for terminal-based coding agents
- MCP-style tool orchestration where possible
- skills, agents, and workflow prompts that work from the terminal environment

## AI experience goals
The app should support a workflow where a model launched inside the Ubuntu terminal can act like a capable coding agent. This means supporting:
- project-aware prompts
- terminal command execution
- file editing and inspection
- context gathering from the workspace
- tool-like actions such as search, read, write, run, and explain
- reusable prompts and agent-like behaviors
- optional MCP-style integrations for tools and services
- memory or workflow state for recurring tasks

## Implementation rules
- Preserve the existing UI and screen structure.
- Add minimal new UI only where necessary.
- Prefer logic integration and services over visual overhauls.
- Reuse the existing terminal stack before introducing new infrastructure.
- Keep features modular so they can be enabled or disabled easily.
- Favor robust, practical functionality over flashy but fragile implementations.

## Where to work
- Android app sources under android/app/src/main/java
- terminal logic under android/app/src/main/java/com/codespace/ide/terminal
- terminal UI under android/app/src/main/java/com/codespace/ide/ui/panes
- AI integration under android/app/src/main/java/com/codespace/ide/ai
- settings and shell screens for small entry points

## Preferred implementation approach
- Start with shell quality-of-life features and AI-ready terminal setup.
- Add session and workspace management next.
- Add agent-style and tool-like features after the core terminal experience is stable.
- Make the AI layer provider-agnostic so multiple models can be used.
- Allow the terminal to support both local and remote execution modes.

## Expected outcome
The result should feel like a premium mobile coding terminal that is not just a shell, but a lightweight AI-native developer workspace with strong terminal capabilities and agent-style workflows.
