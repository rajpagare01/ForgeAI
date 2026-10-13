"use client";

import React, { useState } from "react";
import { useTenant } from "@/lib/tenant-context";
import { Building2, Check, ChevronDown, Plus } from "lucide-react";
import { Badge } from "@/components/ui/badge";

export const OrgSwitcher: React.FC = () => {
  const { currentOrg, organizations, switchOrganization, addOrganization } = useTenant();
  const [isOpen, setIsOpen] = useState(false);
  const [showNewModal, setShowNewModal] = useState(false);
  const [newOrgName, setNewOrgName] = useState("");

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault();
    if (newOrgName.trim()) {
      addOrganization(newOrgName.trim());
      setNewOrgName("");
      setShowNewModal(false);
      setIsOpen(false);
    }
  };

  return (
    <div className="relative">
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="flex items-center gap-2.5 px-3 py-1.5 rounded-lg border border-slate-800 bg-slate-900/90 hover:bg-slate-800/80 transition-all text-sm font-medium text-slate-200"
      >
        <div className="flex h-6 w-6 items-center justify-center rounded-md bg-indigo-600/20 text-indigo-400 border border-indigo-500/30">
          <Building2 className="h-3.5 w-3.5" />
        </div>
        <span className="max-w-[140px] truncate">{currentOrg.name}</span>
        <Badge variant="outline" className="text-[10px] py-0 px-1.5 font-mono text-slate-400">
          {currentOrg.role}
        </Badge>
        <ChevronDown className="h-4 w-4 text-slate-400" />
      </button>

      {isOpen && (
        <>
          <div
            className="fixed inset-0 z-40"
            onClick={() => setIsOpen(false)}
          />
          <div className="absolute right-0 mt-2 w-72 rounded-xl border border-slate-700/80 bg-slate-900/95 p-1.5 shadow-2xl backdrop-blur-xl z-50">
            <div className="px-3 py-2 text-xs font-semibold uppercase tracking-wider text-slate-400">
              Select Organization Context
            </div>
            <div className="space-y-1">
              {organizations.map((org) => {
                const isSelected = org.id === currentOrg.id;
                return (
                  <button
                    key={org.id}
                    onClick={() => {
                      switchOrganization(org.id);
                      setIsOpen(false);
                    }}
                    className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-sm transition-colors ${
                      isSelected
                        ? "bg-indigo-600/15 text-indigo-300 border border-indigo-500/30"
                        : "text-slate-300 hover:bg-slate-800/70"
                    }`}
                  >
                    <div className="flex items-center gap-2.5 truncate">
                      <div className="flex h-6 w-6 shrink-0 items-center justify-center rounded bg-slate-800 text-xs font-bold text-slate-400">
                        {org.name.charAt(0)}
                      </div>
                      <div className="truncate">
                        <div className="font-medium text-slate-200 truncate">{org.name}</div>
                        <div className="text-[11px] text-slate-500 font-mono">{org.slug}</div>
                      </div>
                    </div>
                    {isSelected && <Check className="h-4 w-4 shrink-0 text-indigo-400" />}
                  </button>
                );
              })}
            </div>

            <div className="mt-2 pt-2 border-t border-slate-800">
              <button
                onClick={() => {
                  setShowNewModal(true);
                  setIsOpen(false);
                }}
                className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-xs font-medium text-indigo-400 hover:bg-indigo-500/10 transition-colors"
              >
                <Plus className="h-4 w-4" />
                Create New Organization
              </button>
            </div>
          </div>
        </>
      )}

      {/* Mini Modal for new Organization */}
      {showNewModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
          <div className="w-full max-w-sm rounded-xl border border-slate-700 bg-slate-900 p-5 shadow-2xl">
            <h4 className="text-base font-semibold text-white">Create Organization</h4>
            <p className="text-xs text-slate-400 mt-1">
              Add a new tenant workspace with isolated governance scopes.
            </p>
            <form onSubmit={handleCreate} className="mt-4 space-y-3">
              <input
                type="text"
                placeholder="e.g. Acme Security Platform"
                value={newOrgName}
                onChange={(e) => setNewOrgName(e.target.value)}
                className="w-full rounded-lg border border-slate-700 bg-slate-800 px-3 py-2 text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                autoFocus
              />
              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowNewModal(false)}
                  className="rounded-lg px-3 py-1.5 text-xs text-slate-300 hover:bg-slate-800"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={!newOrgName.trim()}
                  className="rounded-lg bg-indigo-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-indigo-500 disabled:opacity-50"
                >
                  Create Organization
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
