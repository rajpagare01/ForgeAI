import pytest
from app.application.patch_safety_engine import PatchSafetyEngine, PatchSafetyValidationError


def test_reject_protected_branches():
    """Verifies that patch cannot target protected branches directly."""
    with pytest.raises(PatchSafetyValidationError, match="cannot target protected branch 'main'"):
        PatchSafetyEngine.validate_patch_safety(
            target_file="src/index.ts",
            original_content="const x = 1;",
            unified_diff="--- a/src/index.ts\n+++ b/src/index.ts\n@@ -1,1 +1,1 @@\n-const x = 1;\n+const x = 2;",
            target_branch="main",
        )


def test_reject_forbidden_paths():
    """Verifies that patches touching forbidden paths are rejected."""
    forbidden = [
        ".env",
        ".env.production",
        ".git/config",
        "keys/id_rsa",
        "cert.pem",
        ".github/workflows/ci.yml",
    ]

    for path in forbidden:
        with pytest.raises(PatchSafetyValidationError, match="matches restricted pattern"):
            PatchSafetyEngine.validate_patch_safety(
                target_file=path,
                original_content="content",
                unified_diff="--- a/" + path + "\n+++ b/" + path + "\n@@ -1,1 +1,1 @@\n-old\n+new",
            )


def test_reject_diff_exceeding_line_limits():
    """Verifies that patches with > 150 lines changed are rejected."""
    # Create diff with 155 changed lines
    added_lines = "\n".join([f"+line_{i} = {i}" for i in range(155)])
    diff = f"--- a/large.py\n+++ b/large.py\n@@ -1,1 +1,156 @@\n-line_0 = 0\n{added_lines}"

    with pytest.raises(PatchSafetyValidationError, match="exceeds line modification limit"):
        PatchSafetyEngine.validate_patch_safety(
            target_file="src/large.py",
            original_content="line_0 = 0\n",
            unified_diff=diff,
        )


def test_reject_secrets_in_patch():
    """Verifies that patch introducing a raw secret is immediately rejected."""
    raw_secret_replacement = 'AWS_SECRET_KEY = "AKIAIOSFODNN7EXAMPLE"'
    diff = f"--- a/config.py\n+++ b/config.py\n@@ -1,1 +1,1 @@\n-pass\n+{raw_secret_replacement}"

    with pytest.raises(PatchSafetyValidationError, match="contains suspected raw secret"):
        PatchSafetyEngine.validate_patch_safety(
            target_file="src/config.py",
            original_content="pass\n",
            unified_diff=diff,
            proposed_replacement=raw_secret_replacement,
        )


def test_successful_python_in_memory_patch_and_ast_validation():
    """Verifies in-memory patch application and syntax validation for Python without executing code."""
    original = """def add_numbers(a, b):
    return a + b
"""
    diff = """--- a/src/math.py
+++ b/src/math.py
@@ -1,2 +1,3 @@
 def add_numbers(a, b):
+    # Add docstring
     return a + b
"""
    result = PatchSafetyEngine.validate_patch_safety(
        target_file="src/math.py",
        original_content=original,
        unified_diff=diff,
    )

    assert result["valid"] is True
    assert result["syntax_valid"] is True
    assert "# Add docstring" in result["patched_content"]
    assert result["applied_hunks"] == 1


def test_reject_syntax_error_in_python_patch():
    """Verifies that patch causing syntax error is rejected via AST validation."""
    original = """def calculate():
    return 42
"""
    # Diff introduces unclosed parenthesis / invalid syntax
    diff = """--- a/src/calc.py
+++ b/src/calc.py
@@ -1,2 +1,2 @@
 def calculate():
-    return 42
+    return (42 + 
"""
    with pytest.raises(PatchSafetyValidationError, match="static syntax validation failed"):
        PatchSafetyEngine.validate_patch_safety(
            target_file="src/calc.py",
            original_content=original,
            unified_diff=diff,
        )


def test_delimiter_validation_java_patch():
    """Verifies delimiter matching for Java patches."""
    original = """public class Service {
    public void run() {
        System.out.println("running");
    }
}
"""
    # Valid patch
    valid_diff = """--- a/Service.java
+++ b/Service.java
@@ -2,3 +2,3 @@
     public void run() {
-        System.out.println("running");
+        System.out.println("safe run");
     }
"""
    res = PatchSafetyEngine.validate_patch_safety(
        target_file="src/main/java/Service.java",
        original_content=original,
        unified_diff=valid_diff,
    )
    assert res["valid"] is True

    # Invalid patch with unclosed brace
    invalid_diff = """--- a/Service.java
+++ b/Service.java
@@ -2,3 +2,4 @@
     public void run() {
+        if (true) {
         System.out.println("safe run");
     }
"""
    with pytest.raises(PatchSafetyValidationError, match="Unbalanced syntax delimiters"):
        PatchSafetyEngine.validate_patch_safety(
            target_file="src/main/java/Service.java",
            original_content=original,
            unified_diff=invalid_diff,
        )
