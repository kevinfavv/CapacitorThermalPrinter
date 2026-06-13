import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    include: ['test/**/*.spec.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'text-summary', 'html', 'lcov'],
      reportsDirectory: './coverage',
      // On mesure la couverture de la logique métier pure et testable.
      include: ['src/core/**/*.ts', 'src/adapters/**/*.ts', 'src/web.ts'],
      // Fichiers purement déclaratifs (types/contrats sans logique runtime) exclus.
      exclude: [
        'src/definitions.ts',
        'src/index.ts',
        'src/core/text.ts',
        'src/core/models.ts',
        'src/core/options.ts',
      ],
      thresholds: {
        statements: 85,
        branches: 80,
        functions: 85,
        lines: 85,
      },
    },
  },
});
