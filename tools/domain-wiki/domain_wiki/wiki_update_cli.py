import argparse
import html
import json
import os
import re
import shutil
from pathlib import Path

from .confluence import ConfluenceClient
from .git_diff import read_git_diff, read_patches
from .openai import OpenAIClient
from .wiki_update import (
    apply_confirmed_updates,
    render_wiki_result,
    run_wiki_update_review,
)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Update Git Wiki after a dev merge")
    parser.add_argument("--repository", default=".")
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--wiki-directory", required=True)
    parser.add_argument("--seed-directory", required=True)
    parser.add_argument("--pull-request-context", required=True)
    parser.add_argument("--discussion", required=True)
    parser.add_argument("--output-directory", default=".domain-wiki/output")
    args = parser.parse_args(argv)

    repository = Path(args.repository).resolve()
    wiki = Path(args.wiki_directory).resolve()
    seed = Path(args.seed_directory).resolve()
    bootstrap_changed = initialize_wiki(wiki, seed)
    registry = _read_json(seed / "Wiki-Document-Registry.json")
    documents = _read_documents(wiki, registry)
    pull_request = _read_json(Path(args.pull_request_context))
    discussion = _read_json(Path(args.discussion))
    diff = read_git_diff(repository, args.base, args.head)
    mapped_paths = tuple(
        path for path in diff.changed_paths
        if any(_path_matches(path, pattern) for pattern in registry["codePaths"])
    )
    patches = read_patches(repository, diff.base_sha, diff.head_sha, mapped_paths, 180_000)
    evidence = _read_meeting_evidence(registry)
    cheap = OpenAIClient(
        os.environ.get("OPENAI_API_KEY"),
        model=os.environ.get("POLICY_DRAFT_MODEL", "gpt-6-luna"),
        reasoning_effort=os.environ.get("POLICY_DRAFT_REASONING", "low"),
    )
    strong = OpenAIClient(
        os.environ.get("OPENAI_API_KEY"),
        model=os.environ.get("POLICY_REVIEW_MODEL", "gpt-6.1-sol"),
        reasoning_effort=os.environ.get("POLICY_REVIEW_REASONING", "high"),
    )
    result = run_wiki_update_review(
        cheap_client=cheap,
        strong_client=strong,
        documents=documents,
        changed_paths=mapped_paths,
        patches=patches,
        pull_request=pull_request,
        discussion=discussion,
        meeting_evidence=evidence,
    )
    ai_changed = apply_confirmed_updates(wiki, documents, result["accepted"])
    changed = sorted(set(bootstrap_changed + ai_changed))
    result.update(
        {
            "baseSha": diff.base_sha,
            "headSha": diff.head_sha,
            "changedPaths": list(diff.changed_paths),
            "mappedPaths": list(mapped_paths),
            "writtenDocuments": changed,
        }
    )
    output = Path(args.output_directory)
    output.mkdir(parents=True, exist_ok=True)
    (output / "wiki-result.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output / "wiki-comment.md").write_text(
        render_wiki_result(result, changed), encoding="utf-8"
    )


def initialize_wiki(wiki, seed):
    changed = []
    for source in sorted(seed.glob("*")):
        if source.name == "Wiki-Document-Registry.json" or not source.is_file():
            continue
        target = wiki / source.name
        if not target.exists():
            shutil.copyfile(source, target)
            changed.append(source.name)
        elif source.name == "Home.md" and target.read_text(encoding="utf-8").strip() == "테스트입니다":
            shutil.copyfile(source, target)
            changed.append(source.name)
    return changed


def _read_documents(wiki, registry):
    documents = []
    for path in registry["documents"]:
        target = wiki / path
        documents.append({"path": path, "content": target.read_text(encoding="utf-8")})
    return documents


def _read_meeting_evidence(registry):
    configured = registry.get("meetingEvidence", [])
    if not configured:
        return []
    client = ConfluenceClient(
        os.environ.get("CONFLUENCE_BASE_URL", "https://higgs95.atlassian.net/wiki"),
        os.environ.get("ATLASSIAN_EMAIL"),
        os.environ.get("ATLASSIAN_API_TOKEN"),
    )
    evidence = []
    for expected in configured:
        page = client.get_page(str(expected["pageId"]))
        version = str(page.get("version", {}).get("number", ""))
        body = page.get("body", {}).get("storage", {}).get("value", "")
        if version != str(expected["version"]):
            raise ValueError(
                f"Confluence page {expected['pageId']} version changed: "
                f"{expected['version']} -> {version}"
            )
        if _plain(expected["quote"]) not in _plain(body):
            raise ValueError(f"Confluence quote missing from page {expected['pageId']}")
        evidence.append({**expected, "verified": True})
    return evidence


def _read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _path_matches(path, pattern):
    return path.startswith(pattern[:-3]) if pattern.endswith("/**") else path == pattern


def _plain(value):
    value = html.unescape(re.sub(r"<[^>]+>", " ", str(value)))
    return " ".join(value.split())


if __name__ == "__main__":
    main()
