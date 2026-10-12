class AIServiceException(Exception):
    """Base exception for AI Service"""
    pass


class TenantHeaderMissingException(AIServiceException):
    """Raised when mandatory tenant header X-Org-Id is absent"""
    pass


class ModelInferenceException(AIServiceException):
    """Raised when LLM or embedding provider returns an error"""
    pass


class ASTParsingException(AIServiceException):
    """Raised when code cannot be parsed by tree-sitter"""
    pass
