import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const buttonVariants = cva(
  "inline-flex items-center justify-center whitespace-nowrap rounded-md text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-zinc-400 disabled:pointer-events-none disabled:opacity-50 active:scale-[0.99]",
  {
    variants: {
      variant: {
        default:
          "bg-zinc-100 text-zinc-950 hover:bg-white shadow-sm font-medium",
        secondary:
          "bg-[#16161a] text-zinc-200 hover:bg-[#1f1f25] border border-[#27272c]",
        outline:
          "border border-[#27272c] bg-transparent hover:bg-[#18181d] text-zinc-300 hover:text-white",
        ghost:
          "hover:bg-[#18181d] text-zinc-400 hover:text-zinc-100",
        destructive:
          "bg-rose-950/40 text-rose-300 border border-rose-500/30 hover:bg-rose-900/40",
        glow:
          "bg-zinc-100 text-zinc-950 hover:bg-white shadow-sm font-medium border border-zinc-200/20",
        subtle:
          "bg-[#16161b] text-zinc-300 hover:bg-[#1f1f25] border border-[#27272c]",
      },
      size: {
        default: "h-8 px-3 py-1.5",
        sm: "h-7 rounded-md px-2.5 text-[11px]",
        lg: "h-10 rounded-md px-5 text-sm",
        icon: "h-8 w-8 p-0",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, ...props }, ref) => {
    return (
      <button
        className={cn(buttonVariants({ variant, size, className }))}
        ref={ref}
        {...props}
      />
    );
  }
);
Button.displayName = "Button";

export { Button, buttonVariants };
