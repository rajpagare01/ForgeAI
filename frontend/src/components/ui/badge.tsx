import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium tracking-wide transition-colors focus:outline-none",
  {
    variants: {
      variant: {
        default:
          "border border-indigo-500/30 bg-indigo-500/10 text-indigo-300",
        secondary:
          "border border-slate-700 bg-slate-800 text-slate-300",
        success:
          "border border-emerald-500/30 bg-emerald-500/10 text-emerald-300",
        warning:
          "border border-amber-500/30 bg-amber-500/10 text-amber-300",
        destructive:
          "border border-rose-500/30 bg-rose-500/10 text-rose-300",
        outline:
          "border border-slate-700 text-slate-300",
        riskLow:
          "border border-emerald-500/30 bg-emerald-950/40 text-emerald-400 font-semibold",
        riskMedium:
          "border border-amber-500/40 bg-amber-950/40 text-amber-300 font-semibold",
        riskHigh:
          "border border-rose-500/50 bg-rose-950/50 text-rose-300 font-bold shadow-sm shadow-rose-500/20",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return (
    <div className={cn(badgeVariants({ variant }), className)} {...props} />
  );
}

export { Badge, badgeVariants };
