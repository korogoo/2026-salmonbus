import tempfile
import unittest
from pathlib import Path

from domain_wiki.wiki_update import (
    WikiUpdateError,
    apply_confirmed_updates,
    render_wiki_result,
    run_wiki_update_review,
)
from domain_wiki.wiki_update_cli import initialize_wiki


class FakeModel:
    def __init__(self, model, outputs):
        self.model = model
        self.outputs = list(outputs)
        self.calls = []

    def review(self, prompt, schema):
        self.calls.append((prompt, schema))
        return self.outputs.pop(0), {"inputTokens": 1, "outputTokens": 1}


class WikiUpdateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.wiki = self.root / "wiki"
        self.seed = self.root / "seed"
        self.wiki.mkdir()
        self.seed.mkdir()
        (self.wiki / "Home.md").write_text("테스트입니다\n", encoding="utf-8")
        (self.seed / "Home.md").write_text("# 정책 Wiki\n", encoding="utf-8")
        (self.seed / "Policy.md").write_text("현재 동작\n", encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_bootstrap_replaces_test_home_and_adds_missing_documents(self):
        changed = initialize_wiki(self.wiki, self.seed)
        self.assertEqual(["Home.md", "Policy.md"], changed)
        self.assertEqual("# 정책 Wiki\n", (self.wiki / "Home.md").read_text())

    def test_initialized_wiki_does_not_restore_a_missing_seed_document(self):
        (self.wiki / "Home.md").write_text("# 운영 중인 정책 Wiki\n", encoding="utf-8")
        changed = initialize_wiki(self.wiki, self.seed)
        self.assertEqual([], changed)
        self.assertFalse((self.wiki / "Policy.md").exists())

    def test_two_reviews_gate_and_apply_exact_replacement(self):
        proposal = {
            "proposalId": "W1", "document": "Policy.md",
            "currentText": "현재 동작", "replacementText": "머지된 동작",
            "reason": "코드가 변경됨", "codePaths": ["frontend/src/a.ts"],
        }
        cheap = FakeModel(
            "gpt-6-luna",
            [
                {"proposals": [proposal]},
                {"responses": [{"proposalId": "W1", "disposition": "defend", "response": "근거 있음"}]},
            ],
        )
        strong = FakeModel(
            "gpt-6.1-sol",
            [
                {"assessments": [{"proposalId": "W1", "decision": "support", "objections": []}]},
                {"assessments": [{"proposalId": "W1", "decision": "confirmed", "unresolvedCounterevidence": False, "rationale": "확인"}]},
            ],
        )
        result = run_wiki_update_review(
            cheap_client=cheap,
            strong_client=strong,
            documents=[{"path": "Policy.md", "content": "현재 동작\n"}],
            changed_paths=("frontend/src/a.ts",),
            patches={"frontend/src/a.ts": "+new"},
            pull_request={"title": "change", "body": "body"},
            discussion=[{"body": "approved"}],
            meeting_evidence=[{"pageId": "1", "verified": True}],
        )
        self.assertEqual(
            ["draft", "review-1", "author-response", "review-2"], result["stages"]
        )
        self.assertEqual(2, len(cheap.calls))
        self.assertEqual(2, len(strong.calls))
        (self.wiki / "Policy.md").write_text("현재 동작\n", encoding="utf-8")
        changed = apply_confirmed_updates(
            self.wiki, [{"path": "Policy.md", "content": "현재 동작\n"}], result["accepted"]
        )
        self.assertEqual(["Policy.md"], changed)
        self.assertEqual("머지된 동작\n", (self.wiki / "Policy.md").read_text())
        self.assertIn("확정: 1건", render_wiki_result(result, changed))

    def test_unresolved_or_unmapped_proposal_is_rejected(self):
        proposal = {
            "proposalId": "W1", "document": "Policy.md",
            "currentText": "현재 동작", "replacementText": "다른 동작",
            "reason": "추정", "codePaths": ["backend/unrelated.java"],
        }
        cheap = FakeModel("cheap", [{"proposals": [proposal]}, {"responses": []}])
        strong = FakeModel(
            "strong",
            [
                {"assessments": []},
                {"assessments": [{"proposalId": "W1", "decision": "confirmed", "unresolvedCounterevidence": True, "rationale": "근거 부족"}]},
            ],
        )
        result = run_wiki_update_review(
            cheap_client=cheap, strong_client=strong,
            documents=[{"path": "Policy.md", "content": "현재 동작\n"}],
            changed_paths=("frontend/src/a.ts",), patches={},
            pull_request={}, discussion=[], meeting_evidence=[],
        )
        self.assertEqual([], result["accepted"])
        self.assertIn("counterevidence", result["rejected"][0]["gateFailures"])
        self.assertIn("changed-code-evidence", result["rejected"][0]["gateFailures"])

    def test_same_model_is_rejected(self):
        with self.assertRaisesRegex(WikiUpdateError, "must be different"):
            run_wiki_update_review(
                cheap_client=FakeModel("same", []), strong_client=FakeModel("same", []),
                documents=[], changed_paths=(), patches={}, pull_request={},
                discussion=[], meeting_evidence=[],
            )


if __name__ == "__main__":
    unittest.main()
