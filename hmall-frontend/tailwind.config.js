/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{vue,js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        primary: {
          DEFAULT: '#E4393C',
          dark: '#C81623',
          warm: '#FF6B35',
          blue: '#409EFF',
        },
        ink: {
          900: '#1A1D24',
          700: '#2B2F36',
          500: '#5C6470',
          300: '#9AA1AD',
          100: '#E8EAEE',
        },
        admin: {
          sidebar: '#1F2A3D',
          header: '#FFFFFF',
          bg: '#F0F2F5',
        },
      },
      boxShadow: {
        card: '0 1px 2px rgba(27,31,38,.04), 0 4px 16px rgba(27,31,38,.06)',
        lift: '0 8px 28px rgba(27,31,38,.12)',
        glow: '0 4px 20px rgba(228,57,60,.35)',
      },
      borderRadius: {
        xl: '12px',
        '2xl': '16px',
      },
      transitionTimingFunction: {
        spring: 'cubic-bezier(.34,1.56,.64,1)',
      },
      keyframes: {
        float: {
          '0%, 100%': { transform: 'translateY(0px)' },
          '50%': { transform: 'translateY(-10px)' },
        },
        'fade-up': {
          from: { opacity: '0', transform: 'translateY(12px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
        'scale-in': {
          from: { opacity: '0', transform: 'scale(.6)' },
          to: { opacity: '1', transform: 'scale(1)' },
        },
      },
      animation: {
        float: 'float 3s ease-in-out infinite',
        'fade-up': 'fade-up .4s ease-out both',
        'scale-in': 'scale-in .45s cubic-bezier(.34,1.56,.64,1) both',
      },
    },
  },
  plugins: [],
}
