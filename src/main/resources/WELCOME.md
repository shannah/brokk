# Welcome to Brokk!

Brokk is code intelligence for AI.

Check out the README at https://github.com/jbellis/brokk for more details.

## Getting started
- Use `Context -> Edit Files` to add files you want Brokk to modify.
- Use `Context -> Read Files` to add files you want Brokk to see but not change.  However,
  often you should prefer `Context -> Summarize Files` to keep your context streamlined.

Completion Suggestions: Press Ctrl+Space in any dialog to get completions.

## More ways to add context
- Paste text (including stacktraces), and Brokk will automatically parse them for references to
  code sources.
- Use the `Capture Text` button to capture the current output as context; this is particularly
  useful for git commands.  Or you can use `Edit References` to edit all
  files referenced.

## Working with external LLMs
`Edit -> Copy`, or `Copy All` in the context panel right click menu, gathers your entire context and relevant instructions for use with external editors like o1pro.
