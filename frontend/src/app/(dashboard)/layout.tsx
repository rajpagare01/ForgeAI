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
      <div className="min-h-screen bg-[#09090B] text-zinc-100 flex antialiased selection:bg-zinc-800 selection:text-zinc-100">
        {/* Linear-Style Fixed 224px Sidebar */}
        <Suspense fallback={<div className="fixed inset-y-0 left-0 w-56 bg-[#0E0E11] border-r border-[#1C1C21]" />}>
          <Sidebar />
        </Suspense>

        {/* Main Content Area */}
        <div className="flex-1 pl-56 flex flex-col min-w-0">
          <Suspense fallback={<div className="h-12 border-b border-[#1C1C21] bg-[#09090B]/85" />}>
            <TopNav />
          </Suspense>
          <main className="flex-1 px-6 py-5 max-w-[1440px] mx-auto w-full">
            {children}
          </main>
        </div>
      </div>
    </AuthGuard>
  );
}
