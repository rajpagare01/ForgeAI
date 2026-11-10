import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[11px] font-mono font-medium leading-none border transition-colors focus:outline-none",
  {
    variants: {
      variant: {
        default:
          "border-[#26262C] bg-[#141418] text-zinc-300",
        secondary:
          "border-[#1C1C21] bg-[#0E0E11] text-zinc-400",
        success:
          "border-emerald-500/25 bg-emerald-950/20 text-emerald-400",
        warning:
          "border-amber-500/25 bg-amber-950/20 text-amber-400",
        destructive:
          "border-rose-500/25 bg-rose-950/20 text-rose-400",
        violet:
          "border-violet-500/25 bg-violet-950/20 text-violet-400",
        outline:
          "border-[#26262C] bg-transparent text-zinc-400",
        riskLow:
          "border-emerald-500/25 bg-emerald-950/20 text-emerald-400",
        riskMedium:
          "border-amber-500/25 bg-amber-950/20 text-amber-300",
        riskHigh:
          "border-rose-500/30 bg-rose-950/30 text-rose-300 font-semibold",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLDivElement>,
  VariantProps<typeof badgeVariants> { }

function Badge({ className, variant, ...props }: BadgeProps) {
  return (
    <div className={cn(badgeVariants({ variant }), className)} {...props} />
  );
}

export { Badge, badgeVariants };
