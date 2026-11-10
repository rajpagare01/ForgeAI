import type { Metadata } from "next";
import "./globals.css";
import { AuthProvider } from "@/lib/auth-context";
import { TenantProvider } from "@/lib/tenant-context";

export const metadata: Metadata = {
  title: "ForgeAI - Autonomous Enterprise Engineering Governance",
  description: "Unified AI-Powered Traceability, Code Intelligence & Quality Assurance Matrix",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className="dark h-full antialiased">
      <body className="min-h-screen bg-[#09090B] text-zinc-100 font-sans antialiased selection:bg-zinc-800 selection:text-zinc-100">
        <AuthProvider>
          <TenantProvider>
            {children}
          </TenantProvider>
        </AuthProvider>
      </body>
    </html>
  );
}
