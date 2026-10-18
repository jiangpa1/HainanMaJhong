// A sample count is not an elapsed duration. Missing observations break continuity.
export function cpuGuard(env, prefix, defaultMemoryMb) {
  const threshold = Number(env[`${prefix}_CPU`] ?? 0.95);
  const seconds = Number(env[`${prefix}_CPU_SECONDS`] ??
    (env[`${prefix}_CPU_SAMPLES`] === undefined ? 60 : Number(env[`${prefix}_CPU_SAMPLES`]) * 5));
  const memoryMb = Number(env[`${prefix}_MEM_MB`] ?? defaultMemoryMb);
  if (!Number.isFinite(threshold) || threshold <= 0 || threshold > 1 ||
      !Number.isFinite(seconds) || seconds <= 0 || !Number.isFinite(memoryMb) || memoryMb <= 0)
    throw new Error('Invalid resource guard configuration');
  let since = null, previous = null;
  return {
    policy: { cpuThreshold: threshold, cpuSeconds: seconds, memoryMb, maxObservationGapSeconds: 15 },
    reset() { since = previous = null; },
    observe(cpu) {
      const now = performance.now();
      if (!Number.isFinite(cpu) || cpu <= threshold) { this.reset(); return false; }
      if (since === null || previous === null || now - previous > 15000) since = now;
      previous = now;
      return now - since >= seconds * 1000;
    }
  };
}
