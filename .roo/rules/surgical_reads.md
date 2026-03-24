# Context Efficiency Rules
Autonomous Scoping: If a task requires more than 50 lines of context, the agent must not perform a full read. Instead, it must autonomously use codebase_search (Qdrant) and search_files_content (Grep) to identify the specific relevant functions or blocks, and read them in 50 line chunks.

Surgical Intent: The agent must only use read_file_lines to retrieve the identified blocks.

if you're reading logs, chunk them into 50 line bits, and process them sequentially to prevent context overflows.