/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{vue,js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        'mc-dark': '#1a1a2e',
        'mc-darker': '#16161f',
        'mc-accent': '#4ade80',
        'mc-blue': '#60a5fa',
        'mc-error': '#f87171',
        'mc-warn': '#fbbf24',
      },
      fontFamily: {
        'mono': ['JetBrains Mono', 'Fira Code', 'Consolas', 'monospace'],
      },
    },
  },
  plugins: [],
}
