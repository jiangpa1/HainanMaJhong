import fs from 'node:fs';
import path from 'node:path';
import { summarize } from './summary.mjs';
const args=process.argv.slice(2);
if(!args.length)throw Error('Usage: node summarize-results.mjs <run directory> [more directories]');
const rows=args.flatMap(directory=> {
  const report=JSON.parse(fs.readFileSync(path.join(directory,'results.json'),'utf8'));
  return summarize(report).map(row=>({run:path.basename(directory),...row}));
});
console.log(JSON.stringify(rows,null,2));
const columns=['run','tables','players','seconds','recordsVersion','encoding','acceptanceProfile','pushMode','sent','succeeded','rateLimited','successfulRps','failed','errorRate','httpP95Ms','httpP99Ms','broadcastP95Ms','roundDrainSeconds','unmatchedBroadcastRate','unmatchedBroadcastPass','sequenceGaps','missingSequences','pass','stopReason'];
const quote=value=>'"'+String(value??'').replaceAll('"','""')+'"';
fs.writeFileSync('run-summary.csv',[columns.join(','),...rows.map(row=>columns.map(key=>quote(row[key])).join(','))].join('\n')+'\n');
