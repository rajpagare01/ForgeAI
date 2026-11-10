"use client";

import React, { useState } from "react";
import { useTenant } from "@/lib/tenant-context";
import { Building2, Check, ChevronDown, Plus, User, Sparkles } from "lucide-react";
import { Badge } from "@/components/ui/badge";

export const OrgSwitcher: React.FC = () => {
  const {
    activeWorkspace,
    isPersonalWorkspace,
    switchToPersonalWorkspace,
    switchOrganization,
    organizations,
    currentOrg,
    addOrganization,
  } = useTenant();

  const [isOpen, setIsOpen] = useState(false);
  const [showNewModal, setShowNewModal] = useState(false);
  const [newOrgName, setNewOrgName] = useState("");

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (newOrgName.trim()) {
      await addOrganization(newOrgName.trim());
      setNewOrgName("");
      setShowNewModal(false);
      setIsOpen(false);
    }
  };

  const displayName = isPersonalWorkspace
    ? activeWorkspace?.name || "Personal Workspace"
    : currentOrg?.name || "Select Organization";

  return (
    <div className="relative">
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="flex h-7 items-center gap-1.5 px-2 rounded-md border border-[#26262C] bg-[#0E0E11] hover:bg-[#141418] hover:border-zinc-700 transition-colors text-xs font-medium text-zinc-200"
        title="Switch Workspace or Organization"
      >
        <div
          className={`flex h-4 w-4 items-center justify-center rounded text-[10px] ${isPersonalWorkspace
            ? "bg-zinc-800 text-zinc-300"
            : "bg-zinc-800 text-zinc-300"
            }`}
        >
          {isPersonalWorkspace ? (
            <User className="h-3 w-3" />
          ) : (
            <Building2 className="h-3 w-3" />
          )}
        </div>
        <span className="max-w-[100px] truncate text-[11px]">{displayName}</span>
        <ChevronDown className="h-3 w-3 text-zinc-500" />
      </button>

      {isOpen && (
        <>
          <div className="fixed inset-0 z-40" onClick={() => setIsOpen(false)} />
          <div className="absolute left-0 mt-1 w-72 rounded-lg border border-[#26262C] bg-[#0E0E11] p-1.5 shadow-2xl z-50">
            {/* Personal Workspace Section */}
            <div className="px-2 pt-1 pb-1 text-[10px] font-mono font-medium uppercase tracking-wider text-zinc-500">
              Personal Workspace
            </div>
            <div className="space-y-0.5">
              <button
                onClick={() => {
                  switchToPersonalWorkspace();
                  setIsOpen(false);
                }}
                className={`flex w-full items-center justify-between rounded-md px-2 py-1.5 text-left text-xs transition-colors ${isPersonalWorkspace
                  ? "bg-zinc-900 text-zinc-100 border border-zinc-800"
                  : "text-zinc-300 hover:bg-zinc-900/60"
                  }`}
              >
                <div className="flex items-center gap-2 truncate">
                  <div className="flex h-5 w-5 shrink-0 items-center justify-center rounded bg-zinc-800 text-zinc-300 text-[10px]">
                    <User className="h-3 w-3" />
                  </div>
                  <div className="truncate">
                    <div className="font-medium text-zinc-200 truncate text-[11px]">Personal Workspace</div>
                    <div className="text-[10px] text-zinc-500 font-mono truncate">Private projects & repos</div>
                  </div>
                </div>
                {isPersonalWorkspace && (
                  <Check className="h-3.5 w-3.5 shrink-0 text-zinc-100" />
                )}
              </button>
            </div>

            {/* Organizations Section */}
            <div className="mt-2 px-2 pt-1.5 pb-1 border-t border-[#1C1C21] text-[10px] font-mono font-medium uppercase tracking-wider text-zinc-500">
              Organizations
            </div>
            <div className="space-y-0.5 max-h-52 overflow-y-auto">
              {organizations.length === 0 ? (
                <div className="px-2 py-1.5 text-[11px] text-zinc-500 italic">
                  No organizations joined
                </div>
              ) : (
                organizations.map((org) => {
                  const isSelected = !isPersonalWorkspace && org.id === currentOrg?.id;
                  return (
                    <button
                      key={org.id}
                      onClick={() => {
                        switchOrganization(org.id);
                        setIsOpen(false);
                      }}
                      className={`flex w-full items-center justify-between rounded-md px-2 py-1.5 text-left text-xs transition-colors ${isSelected
                        ? "bg-zinc-900 text-zinc-100 border border-zinc-800"
                        : "text-zinc-300 hover:bg-zinc-900/60"
                        }`}
                    >
                      <div className="flex items-center gap-2 truncate">
                        <div className="flex h-5 w-5 shrink-0 items-center justify-center rounded bg-zinc-800 text-[10px] font-bold text-zinc-300">
                          {org.name.charAt(0)}
                        </div>
                        <div className="truncate">
                          <div className="font-medium text-zinc-200 truncate text-[11px]">
                            {org.name}
                          </div>
                          <div className="text-[10px] text-zinc-500 font-mono truncate">
                            {org.slug}
                          </div>
                        </div>
                      </div>
                      {isSelected && (
                        <Check className="h-3.5 w-3.5 shrink-0 text-zinc-100" />
                      )}
                    </button>
                  );
                })
              )}
            </div>

            <div className="mt-1.5 pt-1.5 border-t border-[#1C1C21]">
              <button
                onClick={() => {
                  setShowNewModal(true);
                  setIsOpen(false);
                }}
                className="flex w-full items-center gap-1.5 rounded-md px-2 py-1.5 text-left text-[11px] font-medium text-zinc-400 hover:text-zinc-200 hover:bg-zinc-900/60 transition-colors"
              >
                <Plus className="h-3.5 w-3.5" />
                Create New Organization
              </button>
            </div>
          </div>
        </>
      )}

      {/* Mini Modal for new Organization */}
      {showNewModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-xs p-4">
          <div className="w-full max-w-sm rounded-lg border border-[#26262C] bg-[#0E0E11] p-5 shadow-2xl">
            <h4 className="text-sm font-semibold text-zinc-100">Create Organization</h4>
            <p className="text-xs text-zinc-400 mt-0.5">
              Add a new tenant workspace with isolated governance scopes.
            </p>
            <form onSubmit={handleCreate} className="mt-3 space-y-3">
              <input
                type="text"
                placeholder="e.g. Acme Security Platform"
                value={newOrgName}
                onChange={(e) => setNewOrgName(e.target.value)}
                className="w-full rounded-md border border-[#26262C] bg-[#141418] px-2.5 py-1.5 text-xs text-zinc-100 placeholder-zinc-500 focus:outline-none focus:border-zinc-400"
                autoFocus
              />
              <div className="flex justify-end gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => setShowNewModal(false)}
                  className="rounded-md px-2.5 py-1 text-xs text-zinc-400 hover:bg-zinc-800 hover:text-zinc-200"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={!newOrgName.trim()}
                  className="rounded-md bg-zinc-100 px-3 py-1 text-xs font-medium text-zinc-950 hover:bg-white disabled:opacity-50"
                >
                  Create
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
    </div >
  );
};
