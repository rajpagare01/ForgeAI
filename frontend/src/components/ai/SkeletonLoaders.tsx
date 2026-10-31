"use client";

import React from "react";

export const DocumentCardSkeleton: React.FC = () => {
  return (
    <div className="p-4 rounded-xl border border-slate-800 bg-slate-900/40 space-y-3 animate-pulse">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="h-5 w-20 bg-slate-800 rounded-md"></div>
          <div className="h-5 w-16 bg-slate-800 rounded-md"></div>
        </div>
        <div className="h-4 w-16 bg-slate-800 rounded"></div>
      </div>
      <div className="h-4 w-48 bg-slate-800 rounded"></div>
      <div className="space-y-1.5">
        <div className="h-3 w-full bg-slate-800/60 rounded"></div>
        <div className="h-3 w-3/4 bg-slate-800/60 rounded"></div>
      </div>
      <div className="flex items-center justify-between pt-2 border-t border-slate-800/60">
        <div className="h-3 w-28 bg-slate-800/50 rounded"></div>
        <div className="flex items-center gap-2">
          <div className="h-7 w-20 bg-slate-800 rounded-lg"></div>
          <div className="h-7 w-16 bg-slate-800 rounded-lg"></div>
        </div>
      </div>
    </div>
  );
};

export const CommitReportRowSkeleton: React.FC = () => {
  return (
    <div className="p-4 rounded-xl border border-slate-800 bg-slate-900/40 flex items-center justify-between gap-4 animate-pulse">
      <div className="space-y-2 flex-1">
        <div className="flex items-center gap-2">
          <div className="h-5 w-24 bg-slate-800 rounded-md"></div>
          <div className="h-4 w-20 bg-slate-800 rounded"></div>
          <div className="h-4 w-16 bg-slate-800 rounded"></div>
        </div>
        <div className="h-4 w-64 bg-slate-800 rounded"></div>
        <div className="h-3 w-40 bg-slate-800/60 rounded"></div>
      </div>
      <div className="flex items-center gap-2 shrink-0">
        <div className="h-8 w-28 bg-slate-800 rounded-lg"></div>
      </div>
    </div>
  );
};

export const AutoDocsGridSkeleton: React.FC = () => {
  return (
    <div className="space-y-3">
      {[1, 2, 3, 4, 5].map((i) => (
        <DocumentCardSkeleton key={i} />
      ))}
    </div>
  );
};

export const CommitReportsListSkeleton: React.FC = () => {
  return (
    <div className="space-y-3">
      {[1, 2, 3, 4].map((i) => (
        <CommitReportRowSkeleton key={i} />
      ))}
    </div>
  );
};

