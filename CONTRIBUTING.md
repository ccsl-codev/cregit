# Contributing Guide

Thanks for contributing to `cregit`!

This repository is the `cregit-codev` fork of the original
`cregit` project.

When relevant, please keep changes compatible with the upstream
project and reference the original repository:

https://github.com/cregit/cregit

## Before you open a pull request

For _large changes_, please _open an issue first_ to discuss the proposal.

This is especially important for:

- architectural changes
- cross-module refactors
- new dependencies
- changes to output formats, CLI behavior, or generated artifacts

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org) for all commits.

Examples:

- `feat: add JSON export for blame artifacts`
- `fix(prettyPrint): escape HTML output correctly`
- `docs: rewrite root README in Markdown`

### Allowed types

- `feat`
- `fix`
- `docs`
- `refactor`
- `test`
- `build`
- `ci`
- `chore`
- `perf`

### Scope

A scope is _optional_, but encouraged when it helps.

Examples:

- `fix(remapCommits): handle empty commit messages`
- `feat(tokenize): add intermediate token dump option`

### Breaking changes

Mark breaking changes with `!` and explain them clearly in the pull request.

Example:

- `refactor!: change blame artifact format`

### Do

- Use an imperative, concise description: `fix: handle empty path in clone`
- Prefer a scope when it clarifies the change
- Keep the subject line under ~72 characters
- Reference issues in the body or footer: `Fixes #123`

### Do not

- Do not use vague subjects: `update`, `fix stuff`, `WIP`
- Do not mix unrelated changes in one commit when they can be split cleanly
- Do not put implementation noise in the subject (file lists without a summary)
- Do not skip the type prefix (`added tests` -> `test: cover empty path`)
- Do not use past tense in the subject (`fixed bug` -> `fix: ...`)

## Pull requests

Please follow the repository _pull request template_ when opening a PR.

### Titles

Pull request titles should follow the same [Conventional Commits](https://www.conventionalcommits.org) format as commit messages.

Examples:

- `feat: add artifact manifest generation`
- `docs(persons): convert module README to Markdown`

### Scope

Keep pull requests _focused_.

- one logical change per pull request
- avoid mixing refactors, formatting, and behavior changes unless they are tightly related
- keep changes as small and reviewable as possible

## Testing

For code changes, include _validation steps_ in the pull request.

This can be:

- automated tests
- manual test steps
- example commands and outputs

_Documentation-only_ changes **do not** require testing.

## Documentation

If your change affects behavior, commands, configuration, outputs, or developer workflow, _update the relevant documentation_.

## Compatibility

If your change affects any of the following, call it out clearly in the pull request:

- CLI arguments
- environment variables
- intermediate artifacts
- database schema
- output formats
- generated HTML behavior

## License

By contributing, you agree that your contributions will be licensed under [GPL-3.0+](LICENSE.md), the same license as this project.
