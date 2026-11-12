import logging
from typing import List, Dict, Any, Optional
from tree_sitter import Language, Parser
import tree_sitter_python
import tree_sitter_java

logger = logging.getLogger(__name__)


class TreeSitterParser:
    def __init__(self):
        self.py_lang = Language(tree_sitter_python.language())
        self.java_lang = Language(tree_sitter_java.language())
        self.py_parser = Parser(self.py_lang)
        self.java_parser = Parser(self.java_lang)

    def parse_code(self, file_path: str, code: str) -> Dict[str, Any]:
        """
        Parses source code based on file extension into AST and extracts:
        - symbols: classes and methods/functions with their code chunks
        - edges: calls, imports, extensions
        """
        code_bytes = code.encode("utf-8")
        if file_path.endswith(".py"):
            tree = self.py_parser.parse(code_bytes)
            return self._extract_python_ast(tree.root_node, code_bytes, file_path)
        elif file_path.endswith(".java"):
            tree = self.java_parser.parse(code_bytes)
            return self._extract_java_ast(tree.root_node, code_bytes, file_path)
        else:
            # Fallback text chunking
            return {
                "symbols": [
                    {
                        "symbol_name": file_path,
                        "symbol_type": "MODULE",
                        "content": code,
                    }
                ],
                "edges": [],
            }

    def derive_changed_symbols(self, files: List[Dict[str, Any]]) -> List[str]:
        """
        Statically derives affected symbols (classes, methods, functions, modules)
        from changed files and/or unified diff patches.
        Deterministic static analysis, does not execute repository code.
        """
        derived_symbols: List[str] = []
        seen = set()

        def add_symbol(sym: str):
            if sym and sym not in seen:
                seen.add(sym)
                derived_symbols.append(sym)

        for f in files:
            file_path = f.get("filename") or f.get("file_path") or ""
            if not file_path:
                continue

            status = (f.get("status") or "modified").lower()
            patch = f.get("patch") or ""
            content = f.get("content") or ""

            # 1. Handle deleted file
            if status == "removed":
                if patch:
                    self._extract_symbols_from_diff_lines(patch, file_path, add_symbol, prefix="-")
                add_symbol(file_path)
                continue

            # 2. Parse code if content is available
            if content and file_path.endswith((".java", ".py")):
                try:
                    parsed = self.parse_code(file_path, content)
                    symbols = parsed.get("symbols", [])

                    if patch:
                        added_lines = [
                            line[1:].strip()
                            for line in patch.splitlines()
                            if line.startswith("+") and not line.startswith("+++") and len(line[1:].strip()) > 3
                        ]
                        matched_symbols = []
                        for s in symbols:
                            sym_name = s.get("symbol_name")
                            sym_content = s.get("content", "")
                            bare_name = sym_name.split("#")[-1].split(".")[-1]
                            if bare_name and bare_name in patch:
                                matched_symbols.append(sym_name)
                            elif any(al in sym_content for al in added_lines[:15]):
                                matched_symbols.append(sym_name)

                        if matched_symbols:
                            for ms in matched_symbols:
                                add_symbol(ms)
                        else:
                            classes = [s["symbol_name"] for s in symbols if s.get("symbol_type") == "CLASS"]
                            if classes:
                                for c in classes:
                                    add_symbol(c)
                            else:
                                add_symbol(file_path)
                    else:
                        for s in symbols:
                            add_symbol(s.get("symbol_name"))
                except Exception as e:
                    logger.warning("Error parsing %s with tree-sitter: %s; falling back to file path", file_path, e)
                    add_symbol(file_path)

            elif patch:
                self._extract_symbols_from_diff_lines(patch, file_path, add_symbol, prefix="+")
                if not any(s for s in derived_symbols if s.startswith(file_path.split("/")[-1].split(".")[0])):
                    add_symbol(file_path)
            else:
                add_symbol(file_path)

        return derived_symbols

    def _extract_symbols_from_diff_lines(self, patch: str, file_path: str, add_fn, prefix: str = "+"):
        import re
        for line in patch.splitlines():
            if line.startswith(prefix) and not line.startswith(prefix * 3):
                text = line[1:].strip()
                # Java class/method declaration
                m_java_class = re.search(r'\b(?:class|interface|enum|record)\s+([A-Za-z0-9_]+)', text)
                if m_java_class:
                    add_fn(m_java_class.group(1))
                m_java_method = re.search(r'\b(?:public|protected|private|static|\s)+\s+[\w\<\>\[\],\s]+\s+([A-Za-z0-9_]+)\s*\(', text)
                if m_java_method and m_java_method.group(1) not in ("if", "for", "while", "switch", "catch"):
                    add_fn(m_java_method.group(1))

                # Python class/function declaration
                m_py_class = re.search(r'^class\s+([A-Za-z0-9_]+)', text)
                if m_py_class:
                    add_fn(m_py_class.group(1))
                m_py_func = re.search(r'^def\s+([A-Za-z0-9_]+)', text)
                if m_py_func:
                    add_fn(m_py_func.group(1))

    def _extract_python_ast(self, root_node, code_bytes: bytes, file_path: str) -> Dict[str, Any]:
        symbols: List[Dict[str, Any]] = []
        edges: List[Dict[str, Any]] = []

        def traverse(node, current_scope=""):
            # Import extraction
            if node.type in ("import_statement", "import_from_statement"):
                import_text = code_bytes[node.start_byte : node.end_byte].decode("utf-8").strip()
                target = import_text.replace("import ", "").replace("from ", "").split()[0]
                edges.append({
                    "source_symbol": current_scope or file_path,
                    "target_symbol": target,
                    "edge_type": "IMPORTS",
                })

            # Class definition
            elif node.type == "class_definition":
                name_node = node.child_by_field_name("name")
                class_name = code_bytes[name_node.start_byte : name_node.end_byte].decode("utf-8") if name_node else "UnknownClass"
                full_class_name = f"{current_scope}.{class_name}" if current_scope else class_name

                # Superclasses / extends
                superclasses_node = node.child_by_field_name("superclasses")
                if superclasses_node:
                    for child in superclasses_node.children:
                        if child.type == "identifier":
                            sc_name = code_bytes[child.start_byte : child.end_byte].decode("utf-8")
                            edges.append({
                                "source_symbol": full_class_name,
                                "target_symbol": sc_name,
                                "edge_type": "EXTENDS",
                            })

                class_content = code_bytes[node.start_byte : node.end_byte].decode("utf-8")
                symbols.append({
                    "symbol_name": full_class_name,
                    "symbol_type": "CLASS",
                    "content": class_content,
                })

                for child in node.children:
                    traverse(child, full_class_name)
                return

            # Function / Method definition
            elif node.type == "function_definition":
                name_node = node.child_by_field_name("name")
                func_name = code_bytes[name_node.start_byte : name_node.end_byte].decode("utf-8") if name_node else "anonymous"
                full_func_name = f"{current_scope}.{func_name}" if current_scope else func_name

                func_start = (
                    node.parent.start_byte
                    if node.parent is not None and node.parent.type == "decorated_definition"
                    else node.start_byte
                )
                func_content = code_bytes[func_start : node.end_byte].decode("utf-8")
                symbols.append({
                    "symbol_name": full_func_name,
                    "symbol_type": "METHOD" if current_scope else "FUNCTION",
                    "content": func_content,
                })

                for child in node.children:
                    traverse(child, full_func_name)
                return

            # Call expression
            elif node.type == "call":
                func_node = node.child_by_field_name("function")
                if func_node:
                    call_name = code_bytes[func_node.start_byte : func_node.end_byte].decode("utf-8")
                    if current_scope:
                        edges.append({
                            "source_symbol": current_scope,
                            "target_symbol": call_name,
                            "edge_type": "CALLS",
                        })

            for child in node.children:
                traverse(child, current_scope)

        traverse(root_node, "")
        return {"symbols": symbols, "edges": edges}

    def _extract_java_ast(self, root_node, code_bytes: bytes, file_path: str) -> Dict[str, Any]:
        symbols: List[Dict[str, Any]] = []
        edges: List[Dict[str, Any]] = []
        package_name = ""

        # Find package declaration
        for child in root_node.children:
            if child.type == "package_declaration":
                pkg_text = code_bytes[child.start_byte : child.end_byte].decode("utf-8")
                package_name = pkg_text.replace("package", "").replace(";", "").strip()

        def traverse(node, current_scope=""):
            nonlocal package_name
            # Import declaration
            if node.type == "import_declaration":
                import_text = code_bytes[node.start_byte : node.end_byte].decode("utf-8")
                target = import_text.replace("import", "").replace("static", "").replace(";", "").strip()
                edges.append({
                    "source_symbol": current_scope or package_name or file_path,
                    "target_symbol": target,
                    "edge_type": "IMPORTS",
                })

            # Class or Interface declaration
            elif node.type in ("class_declaration", "interface_declaration"):
                name_node = node.child_by_field_name("name")
                c_name = code_bytes[name_node.start_byte : name_node.end_byte].decode("utf-8") if name_node else "UnknownClass"
                full_class_name = f"{package_name}.{c_name}" if package_name else c_name
                if current_scope:
                    full_class_name = f"{current_scope}${c_name}"

                # Extends / Implements
                for child in node.children:
                    if child.type in ("superclass", "super_interfaces"):
                        for sc_child in child.children:
                            if sc_child.type == "type_identifier":
                                sc_name = code_bytes[sc_child.start_byte : sc_child.end_byte].decode("utf-8")
                                edges.append({
                                    "source_symbol": full_class_name,
                                    "target_symbol": sc_name,
                                    "edge_type": "EXTENDS",
                                })

                class_content = code_bytes[node.start_byte : node.end_byte].decode("utf-8")
                symbols.append({
                    "symbol_name": full_class_name,
                    "symbol_type": "CLASS",
                    "content": class_content,
                })

                for child in node.children:
                    traverse(child, full_class_name)
                return

            # Method declaration
            elif node.type == "method_declaration":
                name_node = node.child_by_field_name("name")
                m_name = code_bytes[name_node.start_byte : name_node.end_byte].decode("utf-8") if name_node else "anonymous"
                full_method_name = f"{current_scope}#{m_name}" if current_scope else m_name

                method_content = code_bytes[node.start_byte : node.end_byte].decode("utf-8")
                symbols.append({
                    "symbol_name": full_method_name,
                    "symbol_type": "METHOD",
                    "content": method_content,
                })

                for child in node.children:
                    traverse(child, full_method_name)
                return

            # Method invocation
            elif node.type == "method_invocation":
                name_node = node.child_by_field_name("name")
                if name_node:
                    invoked = code_bytes[name_node.start_byte : name_node.end_byte].decode("utf-8")
                    if current_scope:
                        edges.append({
                            "source_symbol": current_scope,
                            "target_symbol": invoked,
                            "edge_type": "CALLS",
                        })

            for child in node.children:
                traverse(child, current_scope)

        traverse(root_node, "")
        return {"symbols": symbols, "edges": edges}
