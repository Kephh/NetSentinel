# NetSentinel Dashboard

This package contains the dark-core operational cockpit for NetSentinel.

## Stack

- React + TypeScript + Vite
- Shadcn-style UI primitives (Radix + CVA)
- TanStack Query polling for live telemetry
- Recharts for traffic and latency visualization
- Framer Motion for micro-interactions and transitions
- Redux Toolkit for shared dashboard state
- Lucide React for iconography

## Local Run

1. Install dependencies

   npm install

2. Start dev server

   npm run dev

3. Build and lint

   npm run lint
   npm run build

## Architecture

src/
├── components/      UI atoms and primitives
├── features/        telemetry, traffic map, log stream modules
├── hooks/           polling hooks for telemetry and logs
├── lib/             utility helpers
├── services/        API abstraction + mock fallback generators
└── store/           Redux Toolkit slice and typed hooks

## Data Contract Notes

- The dashboard attempts to fetch from:
  - /api/dashboard/telemetry
  - /api/dashboard/logs
  - /api/dashboard/aegis/scan
- If these APIs are unavailable, it automatically falls back to deterministic mock streams so UX interactions stay testable.

## UX Highlights

- Top telemetry ribbon with 200ms polling cadence
- Center traffic map with backend slide-over panel
- Bottom anomaly-focused log stream with AI scan modal
- Skeleton loading states on all critical zones
- System health breadcrumb with green/yellow/red glow states
