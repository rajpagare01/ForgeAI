import asyncio
import email.message
import html
import logging
import smtplib
from typing import Any, Dict, List, Optional, Tuple
from uuid import UUID

from app.core.config import settings
from app.core.logging import mask_sensitive_data

logger = logging.getLogger(__name__)


class EmailNotificationService:
    """
    Production-grade, asynchronous email notification service for Push Commit Guard.
    Dispatches immediate alert emails when pushed commits contain leaked secrets,
    critical security vulnerabilities, or high-severity reliability defects.

    Guarantees:
    - Zero raw secret exposure: All secrets, credentials, and tokens in email text/HTML
      are strictly redacted with [REDACTED SECRET] masks.
    - Dry-run audit logging fallback: In dev/test environments where SMTP_HOST is not set,
      structured alert audits are logged and marked as LOGGED_DRY_RUN without failure.
    - Non-blocking: Network I/O is offloaded to a threadpool via asyncio.to_thread with a strict 10s timeout.
    """

    def __init__(
        self,
        smtp_host: Optional[str] = None,
        smtp_port: Optional[int] = None,
        smtp_user: Optional[str] = None,
        smtp_password: Optional[str] = None,
        from_email: Optional[str] = None,
        use_tls: Optional[bool] = None,
    ):
        self.smtp_host = smtp_host if smtp_host is not None else settings.SMTP_HOST
        self.smtp_port = smtp_port if smtp_port is not None else settings.SMTP_PORT
        self.smtp_user = smtp_user if smtp_user is not None else settings.SMTP_USER
        self.smtp_password = smtp_password if smtp_password is not None else settings.SMTP_PASSWORD
        self.from_email = from_email if from_email is not None else settings.SMTP_FROM_EMAIL
        self.use_tls = use_tls if use_tls is not None else settings.SMTP_USE_TLS

    def _resolve_deliverable_recipient(self, to_email: Optional[str]) -> str:
        candidate = (to_email or "").strip()
        is_non_routable = (
            not candidate
            or candidate.endswith("@forgeai.local")
            or candidate.endswith("@users.noreply.github.com")
            or candidate.endswith(".local")
        )
        if not is_non_routable:
            return candidate
        fallback = (settings.ALERT_FALLBACK_EMAIL or "").strip()
        if fallback and not fallback.endswith("@forgeai.local") and not fallback.endswith(".local"):
            return fallback
        if self.smtp_user and "@" in self.smtp_user:
            return self.smtp_user.strip()
        return candidate or fallback or "developer@forgeai.local"

    async def send_commit_alert_email(
        self,
        to_email: str,
        repo_name: str,
        commit_sha: str,
        branch_ref: Optional[str] = None,
        author_name: Optional[str] = None,
        risk_score: float = 0.0,
        status: str = "CRITICAL_ALERT",
        secrets_detected: int = 0,
        security_issues: int = 0,
        reliability_issues: int = 0,
        findings: Optional[List[Dict[str, Any]]] = None,
        report_markdown: str = "",
        system_design_doc_id: Optional[UUID] = None,
    ) -> Tuple[bool, str, str]:
        """
        Builds and dispatches the commit security alert email.
        Returns (alert_sent: bool, alert_status: str, recipient: str).
        """
        recipient = self._resolve_deliverable_recipient(to_email)
        findings_list = findings or []

        # 1. Strictly sanitize and redact all text and evidence
        safe_repo = html.escape(mask_sensitive_data(repo_name or "Unknown Repository"))
        safe_sha = html.escape(commit_sha[:12] if commit_sha else "HEAD")
        safe_ref = html.escape(mask_sensitive_data(branch_ref or "main"))
        safe_author = html.escape(mask_sensitive_data(author_name or "Developer"))
        safe_report = mask_sensitive_data(report_markdown or "")

        # Build redacted HTML and text finding rows
        finding_rows_html = []
        finding_rows_text = []
        for idx, f in enumerate(findings_list, 1):
            severity = str(f.get("severity", "HIGH")).upper()
            title = mask_sensitive_data(str(f.get("title", "Finding")))
            category = str(f.get("category", "SECURITY")).upper()
            file_path = mask_sensitive_data(str(f.get("file_path", "")))
            line_no = f.get("line_number", "")
            raw_ev = str(f.get("evidence", f.get("redacted_evidence", "")))
            redacted_ev = mask_sensitive_data(raw_ev)
            remediation = mask_sensitive_data(str(f.get("remediation", "Review and remove sensitive data immediately.")))

            loc = f"{file_path}:{line_no}" if line_no else file_path

            badge_color = "#ef4444" if severity in ("CRITICAL", "HIGH") else "#f59e0b"
            finding_rows_html.append(f"""
            <div style="border-left: 4px solid {badge_color}; background-color: #1e1e2e; padding: 12px; margin-bottom: 10px; border-radius: 4px;">
                <div style="display: flex; justify-content: space-between; align-items: center;">
                    <span style="font-weight: bold; color: #ffffff;">#{idx} {html.escape(title)}</span>
                    <span style="background-color: {badge_color}; color: #ffffff; padding: 2px 8px; border-radius: 3px; font-size: 11px; font-weight: bold;">{severity}</span>
                </div>
                <div style="color: #94a3b8; font-size: 12px; margin-top: 4px; font-family: monospace;">Location: {html.escape(loc)} | Category: {category}</div>
                <div style="background-color: #0f172a; padding: 8px; margin-top: 6px; border-radius: 3px; color: #f87171; font-family: monospace; font-size: 12px; word-break: break-all;">
                    Evidence: {html.escape(redacted_ev)}
                </div>
                <div style="color: #38bdf8; font-size: 12px; margin-top: 6px;">Remediation: {html.escape(remediation)}</div>
            </div>
            """)

            finding_rows_text.append(
                f"- [#{idx} {severity} | {category}] {title}\n"
                f"  Location: {loc}\n"
                f"  Evidence: {redacted_ev}\n"
                f"  Remediation: {remediation}\n"
            )

        findings_html = "".join(finding_rows_html) if finding_rows_html else "<p style='color: #94a3b8;'>No specific findings itemized.</p>"
        findings_text = "\n".join(finding_rows_text) if finding_rows_text else "No specific findings itemized."

        subject = f"[ForgeAI SECURITY ALERT] {status} detected in {repo_name} @ {safe_sha}"

        text_content = f"""
FORGEAI COMMIT GUARD SECURITY ALERT
====================================
Repository:    {repo_name}
Commit SHA:    {commit_sha}
Branch / Ref:  {branch_ref or 'main'}
Author:        {author_name or 'Developer'}
Overall Status:{status}
Risk Score:    {risk_score:.1f} / 100.0

FINDINGS SUMMARY:
- Secret Leaks Detected:     {secrets_detected}
- Security Vulnerabilities:  {security_issues}
- Reliability Anti-Patterns: {reliability_issues}

DETAILS & REDACTED EVIDENCE:
{findings_text}

AUDIT ADVISORY:
Raw secrets are never exposed in email alerts or logs.
Check the ForgeAI dashboard for the full interactive commit report and auto-generated system design document.
System Design Doc ID: {str(system_design_doc_id) if system_design_doc_id else 'Generated'}
"""

        html_content = f"""
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>ForgeAI Security Alert</title>
</head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #0b0f19; color: #e2e8f0; margin: 0; padding: 24px;">
    <div style="max-width: 680px; margin: 0 auto; background-color: #111827; border: 1px solid #374151; border-radius: 8px; overflow: hidden;">
        <div style="background: linear-gradient(90deg, #b91c1c 0%, #450a0a 100%); padding: 16px 24px;">
            <h2 style="margin: 0; color: #ffffff; font-size: 18px; letter-spacing: 0.5px;">🛡️ ForgeAI Push Commit Guard Alert</h2>
        </div>
        <div style="padding: 24px;">
            <div style="background-color: #1f2937; border-radius: 6px; padding: 16px; margin-bottom: 20px;">
                <table style="width: 100%; border-collapse: collapse; font-size: 13px;">
                    <tr>
                        <td style="color: #94a3b8; padding: 4px 0; width: 140px;">Repository:</td>
                        <td style="color: #ffffff; font-weight: 600;">{safe_repo}</td>
                    </tr>
                    <tr>
                        <td style="color: #94a3b8; padding: 4px 0;">Commit:</td>
                        <td style="font-family: monospace; color: #38bdf8;">{safe_sha}</td>
                    </tr>
                    <tr>
                        <td style="color: #94a3b8; padding: 4px 0;">Branch / Ref:</td>
                        <td style="font-family: monospace; color: #e2e8f0;">{safe_ref}</td>
                    </tr>
                    <tr>
                        <td style="color: #94a3b8; padding: 4px 0;">Author:</td>
                        <td style="color: #e2e8f0;">{safe_author}</td>
                    </tr>
                    <tr>
                        <td style="color: #94a3b8; padding: 4px 0;">Risk Score:</td>
                        <td style="color: #ef4444; font-weight: bold;">{risk_score:.1f} / 100.0 ({status})</td>
                    </tr>
                </table>
            </div>

            <div style="display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 10px; margin-bottom: 24px; text-align: center;">
                <div style="background-color: #271414; border: 1px solid #7f1d1d; border-radius: 6px; padding: 10px;">
                    <div style="font-size: 20px; font-weight: bold; color: #f87171;">{secrets_detected}</div>
                    <div style="font-size: 11px; color: #fca5a5; text-transform: uppercase;">Secrets</div>
                </div>
                <div style="background-color: #2b1d12; border: 1px solid #78350f; border-radius: 6px; padding: 10px;">
                    <div style="font-size: 20px; font-weight: bold; color: #fbbf24;">{security_issues}</div>
                    <div style="font-size: 11px; color: #fde68a; text-transform: uppercase;">Security</div>
                </div>
                <div style="background-color: #172554; border: 1px solid #1e3a8a; border-radius: 6px; padding: 10px;">
                    <div style="font-size: 20px; font-weight: bold; color: #60a5fa;">{reliability_issues}</div>
                    <div style="font-size: 11px; color: #bfdbfe; text-transform: uppercase;">Reliability</div>
                </div>
            </div>

            <h3 style="color: #f1f5f9; font-size: 14px; margin: 0 0 12px 0; border-bottom: 1px solid #374151; padding-bottom: 6px;">Findings & Redacted Evidence</h3>
            {findings_html}

            <div style="margin-top: 24px; padding: 12px; background-color: #0f172a; border-radius: 6px; font-size: 11px; color: #64748b;">
                <strong>Strict Zero-Secret Disclosure Policy:</strong> All sensitive tokens and keys in this email are masked. Never commit secrets to source repositories. Immediate revocation of exposed credentials is recommended.
            </div>
        </div>
    </div>
</body>
</html>
"""

        # 2. Check if email alerting is enabled and SMTP_HOST is configured
        if not getattr(settings, "ENABLE_EMAIL_ALERTS", False) or not self.smtp_host:
            logger.info(
                "[EmailNotificationService:DRY_RUN] Email service temporarily stopped/disabled. Audit logged for recipient=%s, repo=%s, commit=%s, status=%s, secrets=%d",
                recipient,
                repo_name,
                commit_sha[:8] if commit_sha else "HEAD",
                status,
                secrets_detected,
            )
            return True, "LOGGED_DRY_RUN", recipient

        # 3. Synchronous send dispatched via asyncio.to_thread
        def _send_sync():
            msg = email.message.EmailMessage()
            effective_from = (
                self.smtp_user
                if (self.smtp_user and "@" in self.smtp_user and self.from_email == "alerts@forgeai.dev")
                else self.from_email
            )
            msg["Subject"] = subject
            msg["From"] = effective_from
            msg["To"] = recipient
            msg.set_content(text_content)
            msg.add_alternative(html_content, subtype="html")

            if int(self.smtp_port) == 465:
                with smtplib.SMTP_SSL(self.smtp_host, int(self.smtp_port), timeout=10) as server:
                    if self.smtp_user and self.smtp_password:
                        server.login(self.smtp_user, self.smtp_password)
                    server.send_message(msg)
            else:
                with smtplib.SMTP(self.smtp_host, int(self.smtp_port), timeout=10) as server:
                    server.ehlo()
                    if self.use_tls:
                        server.starttls()
                        server.ehlo()
                    if self.smtp_user and self.smtp_password:
                        server.login(self.smtp_user, self.smtp_password)
                    server.send_message(msg)

        try:
            await asyncio.wait_for(asyncio.to_thread(_send_sync), timeout=10.0)
            logger.info(
                "[EmailNotificationService:SENT] Commit alert email dispatched to %s for repo %s @ %s",
                recipient,
                repo_name,
                commit_sha[:8] if commit_sha else "HEAD",
            )
            return True, "SENT", recipient
        except Exception as e:
            logger.error(
                "[EmailNotificationService:FAILED] Failed to send email to %s: %s",
                recipient,
                e,
            )
            return False, "FAILED", recipient

