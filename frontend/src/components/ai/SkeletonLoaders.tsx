"use client";

import React from "react";

export const DocumentCardSkeleton: React.FC = () => {
  return (
    <div className="p-3.5 rounded-lg border border-[#1C1C21] bg-[#0E0E11] space-y-2.5 animate-pulse">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="h-4 w-20 bg-zinc-800/60 rounded"></div>
          <div className="h-4 w-16 bg-zinc-800/60 rounded"></div>
        </div>
        <div className="h-3.5 w-14 bg-zinc-800/60 rounded"></div>
      </div>
      <div className="h-3.5 w-48 bg-zinc-800/60 rounded"></div>
      <div className="space-y-1">
        <div className="h-2.5 w-full bg-zinc-800/40 rounded"></div>
        <div className="h-2.5 w-3/4 bg-zinc-800/40 rounded"></div>
      </div>
      <div className="flex items-center justify-between pt-2 border-t border-[#1C1C21]">
        <div className="h-3 w-28 bg-zinc-800/40 rounded"></div>
        <div className="flex items-center gap-1.5">
          <div className="h-6 w-16 bg-zinc-800/60 rounded-md"></div>
          <div className="h-6 w-14 bg-zinc-800/60 rounded-md"></div>
        </div>
      </div>
    </div>
  );
};

export const CommitReportRowSkeleton: React.FC = () => {
  return (
    <div className="p-3 rounded-md border border-[#1C1C21] bg-[#0E0E11] flex items-center justify-between gap-4 animate-pulse">
      <div className="space-y-1.5 flex-1">
        <div className="flex items-center gap-2">
          <div className="h-4 w-20 bg-zinc-800/60 rounded"></div>
          <div className="h-3.5 w-16 bg-zinc-800/60 rounded"></div>
          <div className="h-3.5 w-12 bg-zinc-800/60 rounded"></div>
        </div>
        <div className="h-3.5 w-60 bg-zinc-800/60 rounded"></div>
        <div className="h-3 w-36 bg-zinc-800/40 rounded"></div>
      </div>
      <div className="flex items-center gap-2 shrink-0">
        <div className="h-7 w-24 bg-zinc-800/60 rounded-md"></div>
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

