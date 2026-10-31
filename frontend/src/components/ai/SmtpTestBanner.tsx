"use client";

import React, { useState, useEffect } from "react";
import {
  Mail,
  Send,
  CheckCircle2,
  AlertCircle,
  ShieldCheck,
  RefreshCw,
  X,
  ExternalLink,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { apiClient } from "@/lib/api-client";

interface SmtpTestBannerProps {
  defaultRecipient?: string;
  repoName?: string;
  onNotification?: (type: "success" | "error" | "info", title: string, message: string) => void;
}

export const SmtpTestBanner: React.FC<SmtpTestBannerProps> = ({
  defaultRecipient = "",
  repoName = "ForgeAI-SMTP-Verification",
  onNotification,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [recipient, setRecipient] = useState(defaultRecipient);
  const [isLoading, setIsLoading] = useState(false);
  const [result, setResult] = useState<{
    alert_sent: boolean;
    alert_status: string;
    recipient: string;
    smtp_configured: boolean;
    smtp_host: string | null;
  } | null>(null);
  const [smtpStatusKnown, setSmtpStatusKnown] = useState<boolean | null>(null);

  // Sync recipient with defaultRecipient or forgeai_user in localStorage
  useEffect(() => {
    if (!recipient && typeof window !== "undefined") {
      try {
        const rawUser = localStorage.getItem("forgeai_user");
        if (rawUser) {
          const parsed = JSON.parse(rawUser);
          if (parsed?.email) setRecipient(parsed.email);
        }
      } catch {}
    }
  }, [recipient]);

  const handleSendTestEmail = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setResult(null);

    try {
      const res = await apiClient.post<{
        alert_sent: boolean;
        alert_status: string;
        recipient: string;
        smtp_configured: boolean;
        smtp_host: string | null;
      }>("/api/v1/ai/notifications/test-email", {
        to_email: recipient.trim() || undefined,
        repo_name: repoName,
      });

      setResult(res);
      setSmtpStatusKnown(res.smtp_configured);

      if (res.alert_sent && res.alert_status === "SENT") {
        onNotification &&
          onNotification(
            "success",
            "SMTP Alert Delivered",
            `Test security alert successfully sent to ${res.recipient} via ${res.smtp_host || "SMTP"}.`
          );
      } else if (res.alert_status === "LOGGED_DRY_RUN") {
        onNotification &&
          onNotification(
            "info",
            "Dry-Run Audit Logged",
            `SMTP is unconfigured. Alert was audit-logged for ${res.recipient} without failing.`
          );
      } else {
        onNotification &&
          onNotification(
            "error",
            "Delivery Failed",
            `Failed to send commit alert to ${res.recipient}.`
          );
      }
    } catch (err: any) {
      console.warn("SMTP test error:", err);
      onNotification &&
        onNotification(
          "error",
          "SMTP Verification Error",
          err?.message || "Failed to reach email verification endpoint."
        );
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="flex items-center gap-2">
      {/* SMTP Status Pill */}
      <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-mono border border-slate-700 bg-slate-900/90 shadow-sm">
        <span
          className={`h-2 w-2 rounded-full ${
            smtpStatusKnown === true
              ? "bg-emerald-400 animate-pulse"
              : smtpStatusKnown === false
              ? "bg-amber-400"
              : "bg-sky-400 animate-pulse"
          }`}
        />
        <span className="text-slate-300 font-medium">
          {smtpStatusKnown === true
            ? "SMTP Active"
            : smtpStatusKnown === false
            ? "Dry-Run Mode"
            : "Email Alert Guard"}
        </span>
      </div>

      {/* Trigger Button */}
      <Button
        variant="outline"
        size="sm"
        onClick={() => setIsOpen(true)}
        className="h-8 text-xs gap-1.5 border-slate-700 bg-slate-900/90 hover:bg-slate-800 hover:text-white"
      >
        <Mail className="h-3.5 w-3.5 text-sky-400" />
        Send Test Alert Email
      </Button>

      {/* Modal Dialog */}
      {isOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4 animate-in fade-in duration-150">
          <div className="relative w-full max-w-md rounded-2xl border border-sky-500/40 bg-slate-950 p-6 shadow-2xl space-y-4">
            <div className="flex items-center justify-between border-b border-slate-800 pb-3">
              <div className="flex items-center gap-2">
                <div className="p-2 rounded-lg bg-sky-950/60 border border-sky-500/30 text-sky-400">
                  <Mail className="h-4 w-4" />
                </div>
                <div>
                  <h4 className="text-sm font-bold text-white">SMTP Delivery Verification</h4>
                  <p className="text-[11px] text-slate-400">Send an immediate test alert to verify your email pipeline.</p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => {
                  setIsOpen(false);
                  setResult(null);
                }}
                className="text-slate-400 hover:text-white rounded-lg p-1 hover:bg-slate-900"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <form onSubmit={handleSendTestEmail} className="space-y-3.5">
              <div className="space-y-1.5">
                <label className="text-xs font-semibold text-slate-300">Recipient Email:</label>
                <input
                  type="email"
                  value={recipient}
                  onChange={(e) => setRecipient(e.target.value)}
                  placeholder="e.g. developer@company.com"
                  required
                  className="w-full px-3 py-2 text-xs rounded-xl border border-slate-700 bg-slate-900 text-white placeholder-slate-500 focus:outline-none focus:border-sky-500 font-mono"
                />
                <p className="text-[10px] text-slate-500">
                  Non-routable emails (noreply/local) will automatically route to ALERT_FALLBACK_EMAIL or SMTP user.
                </p>
              </div>

              {result && (
                <div
                  className={`p-3 rounded-xl border text-xs space-y-1 ${
                    result.alert_sent && result.alert_status === "SENT"
                      ? "border-emerald-500/50 bg-emerald-950/30 text-emerald-200"
                      : result.alert_status === "LOGGED_DRY_RUN"
                      ? "border-amber-500/50 bg-amber-950/30 text-amber-200"
                      : "border-rose-500/50 bg-rose-950/30 text-rose-200"
                  }`}
                >
                  <div className="flex items-center gap-1.5 font-bold">
                    {result.alert_status === "SENT" ? (
                      <CheckCircle2 className="h-4 w-4 text-emerald-400" />
                    ) : (
                      <AlertCircle className="h-4 w-4 text-amber-400" />
                    )}
                    <span>Status: {result.alert_status}</span>
                  </div>
                  <div className="font-mono text-[11px] text-slate-300">
                    Recipient: {result.recipient}
                  </div>
                  <div className="font-mono text-[11px] text-slate-400">
                    SMTP Host: {result.smtp_host || "Unconfigured (Dry-Run Mode)"}
                  </div>
                </div>
              )}

              <div className="flex items-center justify-end gap-2 pt-2 border-t border-slate-800">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    setIsOpen(false);
                    setResult(null);
                  }}
                  className="text-xs h-8 border-slate-700"
                >
                  Close
                </Button>
                <Button
                  type="submit"
                  size="sm"
                  disabled={isLoading}
                  className="text-xs h-8 gap-1.5 bg-sky-600 hover:bg-sky-500 text-white"
                >
                  {isLoading ? (
                    <RefreshCw className="h-3 w-3 animate-spin" />
                  ) : (
                    <Send className="h-3 w-3" />
                  )}
                  {isLoading ? "Sending..." : "Send Test Email"}
                </Button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};

