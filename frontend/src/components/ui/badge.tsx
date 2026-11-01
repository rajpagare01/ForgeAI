import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[10px] font-mono font-medium tracking-tight transition-colors focus:outline-none",
  {
    variants: {
      variant: {
        default:
          "border border-[#27272c] bg-[#18181d] text-zinc-300",
        secondary:
          "border border-[#27272c] bg-[#141418] text-zinc-400",
        success:
          "border border-emerald-500/25 bg-emerald-950/40 text-emerald-400",
        warning:
          "border border-amber-500/25 bg-amber-950/40 text-amber-400",
        destructive:
          "border border-rose-500/25 bg-rose-950/40 text-rose-400",
        outline:
          "border border-[#27272c] text-zinc-400",
        riskLow:
          "border border-emerald-500/25 bg-emerald-950/40 text-emerald-400",
        riskMedium:
          "border border-amber-500/25 bg-amber-950/40 text-amber-300",
        riskHigh:
          "border border-rose-500/30 bg-rose-950/50 text-rose-300 font-semibold",
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
