import pytest
from app.infrastructure.parser.tree_sitter_parser import TreeSitterParser


def test_tree_sitter_ast_extraction_java():
    parser = TreeSitterParser()
    java_code = """
    package com.forgeai.example;

    import java.util.List;
    import com.forgeai.service.PaymentService;

    public class OrderProcessor extends BaseProcessor {
        private PaymentService paymentService;

        public void processOrder(String orderId) {
            paymentService.charge(orderId);
        }
    }
    """
    result = parser.parse_code("OrderProcessor.java", java_code)
    symbols = result["symbols"]
    edges = result["edges"]

    # 1. Assert Class Symbol
    class_symbols = [s for s in symbols if s["symbol_type"] == "CLASS"]
    assert len(class_symbols) >= 1
    assert "com.forgeai.example.OrderProcessor" in class_symbols[0]["symbol_name"]

    # 2. Assert Method Symbol
    method_symbols = [s for s in symbols if s["symbol_type"] == "METHOD"]
    assert len(method_symbols) >= 1
    assert any("processOrder" in s["symbol_name"] for s in method_symbols)

    # 3. Assert Edges: IMPORTS
    imports = [e for e in edges if e["edge_type"] == "IMPORTS"]
    assert len(imports) >= 1
    assert any("PaymentService" in e["target_symbol"] for e in imports)

    # 4. Assert Edges: EXTENDS
    extends = [e for e in edges if e["edge_type"] == "EXTENDS"]
    assert len(extends) >= 1
    assert extends[0]["target_symbol"] == "BaseProcessor"

    # 5. Assert Edges: CALLS
    calls = [e for e in edges if e["edge_type"] == "CALLS"]
    assert len(calls) >= 1
    assert any("charge" in e["target_symbol"] for e in calls)


def test_tree_sitter_ast_extraction_python():
    parser = TreeSitterParser()
    py_code = """
import os
from app.services import BillingService

class UserAccount(BaseModel):
    def renew_subscription(self, plan_id):
        BillingService.process(plan_id)
    """
    result = parser.parse_code("account.py", py_code)
    symbols = result["symbols"]
    edges = result["edges"]

    class_symbols = [s for s in symbols if s["symbol_type"] == "CLASS"]
    assert len(class_symbols) == 1
    assert class_symbols[0]["symbol_name"] == "UserAccount"

    method_symbols = [s for s in symbols if s["symbol_type"] == "METHOD"]
    assert len(method_symbols) == 1
    assert method_symbols[0]["symbol_name"] == "UserAccount.renew_subscription"

    calls = [e for e in edges if e["edge_type"] == "CALLS"]
    assert len(calls) >= 1
    assert any("process" in e["target_symbol"] for e in calls)
