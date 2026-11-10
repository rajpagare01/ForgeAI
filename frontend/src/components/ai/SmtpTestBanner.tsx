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
      <div className="flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-mono border border-[#26262C] bg-[#0E0E11]">
        <span
          className={`h-1.5 w-1.5 rounded-full ${
            smtpStatusKnown === true
              ? "bg-emerald-400"
              : smtpStatusKnown === false
              ? "bg-amber-400"
              : "bg-zinc-400"
          }`}
        />
        <span className="text-zinc-300">
          {smtpStatusKnown === true
            ? "SMTP Active"
            : smtpStatusKnown === false
            ? "Dry-Run"
            : "Email Guard"}
        </span>
      </div>

      {/* Trigger Button */}
      <Button
        variant="secondary"
        size="sm"
        onClick={() => setIsOpen(true)}
        className="h-7 text-xs gap-1.5"
      >
        <Mail className="h-3 w-3 text-zinc-400" />
        Verify Email Pipeline
      </Button>

      {/* Modal Dialog */}
      {isOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-xs p-4 animate-in fade-in duration-150">
          <div className="relative w-full max-w-md rounded-lg border border-[#26262C] bg-[#0E0E11] p-5 shadow-2xl space-y-3">
            <div className="flex items-center justify-between border-b border-[#1C1C21] pb-3">
              <div className="flex items-center gap-2">
                <div className="p-1.5 rounded-md bg-[#141418] border border-[#26262C] text-zinc-300">
                  <Mail className="h-3.5 w-3.5" />
                </div>
                <div>
                  <h4 className="text-xs font-semibold text-zinc-100">SMTP Delivery Verification</h4>
                  <p className="text-[11px] text-zinc-400">Send an immediate test alert to verify your email pipeline.</p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => {
                  setIsOpen(false);
                  setResult(null);
                }}
                className="text-zinc-400 hover:text-zinc-100 rounded-md p-1 hover:bg-zinc-800 transition-colors"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <form onSubmit={handleSendTestEmail} className="space-y-3">
              <div className="space-y-1">
                <label className="text-[11px] font-medium text-zinc-300">Recipient Email</label>
                <input
                  type="email"
                  value={recipient}
                  onChange={(e) => setRecipient(e.target.value)}
                  placeholder="e.g. developer@company.com"
                  required
                  className="w-full px-2.5 py-1 text-xs rounded-md border border-[#26262C] bg-[#141418] text-zinc-100 placeholder-zinc-500 focus:outline-none focus:border-zinc-400 font-mono"
                />
                <p className="text-[10px] text-zinc-500">
                  Non-routable emails automatically route to ALERT_FALLBACK_EMAIL or SMTP user.
                </p>
              </div>

              {result && (
                <div
                  className={`p-2.5 rounded-md border text-xs space-y-1 ${
                    result.alert_sent && result.alert_status === "SENT"
                      ? "border-emerald-500/30 bg-emerald-950/20 text-emerald-300"
                      : result.alert_status === "LOGGED_DRY_RUN"
                      ? "border-amber-500/30 bg-amber-950/20 text-amber-300"
                      : "border-rose-500/30 bg-rose-950/20 text-rose-300"
                  }`}
                >
                  <div className="flex items-center gap-1.5 font-medium">
                    {result.alert_status === "SENT" ? (
                      <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" />
                    ) : (
                      <AlertCircle className="h-3.5 w-3.5 text-amber-400" />
                    )}
                    <span>Status: {result.alert_status}</span>
                  </div>
                  <div className="font-mono text-[10px] text-zinc-400">
                    Recipient: {result.recipient}
                  </div>
                  <div className="font-mono text-[10px] text-zinc-500">
                    SMTP Host: {result.smtp_host || "Unconfigured (Dry-Run Mode)"}
                  </div>
                </div>
              )}

              <div className="flex items-center justify-end gap-2 pt-2 border-t border-[#1C1C21]">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => {
                    setIsOpen(false);
                    setResult(null);
                  }}
                  className="text-xs h-7"
                >
                  Close
                </Button>
                <Button
                  type="submit"
                  size="sm"
                  disabled={isLoading}
                  className="text-xs h-7 gap-1.5 bg-zinc-100 hover:bg-white text-zinc-950 font-medium"
                >
                  {isLoading ? (
                    <RefreshCw className="h-3 w-3 animate-spin" />
                  ) : (
                    <Send className="h-3 w-3" />
                  )}
                  {isLoading ? "Sending..." : "Send Test"}
                </Button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};

