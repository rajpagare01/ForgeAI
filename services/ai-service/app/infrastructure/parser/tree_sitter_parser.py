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

                func_content = code_bytes[node.start_byte : node.end_byte].decode("utf-8")
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
