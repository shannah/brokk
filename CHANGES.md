# 0.5.0

## Features
- Git log and stash
- Search in fragment preview
- Add individual references to context

## Fixes
- AutoContext builds asynchronously now instead of blocking operations in large codebases
- Symbol Usage autocomplete is more intutive now

# 0.4.2

## Features
- Light + Dark themes

## Fixes
- Fix bad interaction between search summarization and prefix compression

# 0.4.1

## Features
- Multi-project support
- Workspace UI layout and current context are saved and restored 

## Fixes
- Improved symbol name completion for Find Usages

# 0.4.0
## Features
- Swing UI
- Search is even better

## Fixes
- Too many to list

# 0.3.0

## Features
- New agentic /search command offers the most powerful code RAG anywhere

## Fixes
- Corner cases of /usages

# 0.2.1

## Features
- Anthropic models default to sonnet 3.7
- Brokk's build migrated to SBT


# 0.2.0

## Features
- Significantly better ranking of classes for Auto context

## Fixes
- Multiple improvements to CPG creation at startup


# 0.1.1

## Features
- Enhanced `/undo` to revert changes to file contents as well as context
- Added `/redo` command for redoing changes

## Fixes
- Summaries now include inner classes
- Improved output capture by removing terminal codes
