import React, { Suspense } from "react";
import { Sidebar } from "@/components/navigation/Sidebar";
import { TopNav } from "@/components/navigation/TopNav";
import { AuthGuard } from "@/components/auth/AuthGuard";

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <AuthGuard>
      <div className="min-h-screen bg-[#090d16] text-slate-100 flex">
        {/* Fixed Sidebar */}
        <Suspense fallback={<div className="fixed inset-y-0 left-0 w-64 bg-slate-950/80 border-r border-slate-800" />}>
          <Sidebar />
        </Suspense>

        {/* Main Content Area */}
        <div className="flex-1 pl-64 flex flex-col min-w-0">
          <Suspense fallback={<div className="h-16 border-b border-slate-800 bg-slate-950/70" />}>
            <TopNav />
          </Suspense>
          <main className="flex-1 p-6 lg:p-8 max-w-7xl mx-auto w-full">
            {children}
          </main>
        </div>
      </div>
    </AuthGuard>
  );
}
