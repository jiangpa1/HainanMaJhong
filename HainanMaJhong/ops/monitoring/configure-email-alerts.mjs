import fs from 'node:fs';
import path from 'node:path';
const args = process.argv.slice(2);
const value = name => args.includes(name) ? args[args.indexOf(name) + 1] : undefined;
const configFile = value('--config');
if (!configFile) throw Error('Usage: node configure-email-alerts.mjs --config private/email.local.json --init [--to address] | --render');
const file = path.resolve(configFile), directory = path.dirname(file);
fs.mkdirSync(directory, { recursive: true });
if (args.includes('--init')) {
  if (fs.existsSync(file)) throw Error('Refusing to overwrite existing mail configuration');
  fs.writeFileSync(file, JSON.stringify({
    to: value('--to') || 'recipient@example.invalid', from: '', smarthost: 'smtp.example.invalid:587',
    username: '', passwordFile: 'smtp-password.local', outputFile: 'alertmanager.local.yml', forceImplicitTls: false
  }, null, 2) + '\n', { mode: 0o600 });
  console.log('Private email template created; fill sender, SMTP settings and password file locally. No mail sent.');
} else if (args.includes('--render')) {
  const c = JSON.parse(fs.readFileSync(file, 'utf8'));
  for (const key of ['to', 'from', 'smarthost', 'username', 'passwordFile', 'outputFile'])
    if (typeof c[key] !== 'string' || !c[key] || /[\r\n\0]/.test(c[key]) || c[key].includes('.invalid'))
      throw Error(`Missing or placeholder mail setting: ${key}`);
  for (const key of ['to', 'from']) if (!/^[^\s<>@]+@[^\s<>@]+\.[^\s<>@]+$/.test(c[key])) throw Error(`Invalid ${key}`);
  if (!/^[a-zA-Z0-9.-]+:\d+$/.test(c.smarthost)) throw Error('smarthost must be host:port');
  // Current Alertmanager auto-selects implicit TLS on port 465; other ports use STARTTLS.
  if (c.forceImplicitTls !== undefined && typeof c.forceImplicitTls !== 'boolean') throw Error('forceImplicitTls must be boolean');
  const passwordFile = path.resolve(directory, c.passwordFile);
  if (!fs.existsSync(passwordFile) || !fs.statSync(passwordFile).isFile() || !fs.readFileSync(passwordFile, 'utf8').trim()) throw Error('Create a nonempty SMTP authorization-code file first');
  const output = path.resolve(directory, c.outputFile);
  if (path.dirname(output) !== directory || output === file || output === passwordFile) throw Error('Output must be a separate file in the private config directory');
  const config = {
    global: { resolve_timeout: '1m', smtp_smarthost: c.smarthost, smtp_from: c.from,
      smtp_auth_username: c.username, smtp_auth_password_file: passwordFile, smtp_require_tls: true },
    route: { receiver: 'operations-email', group_by: ['alertname', 'group', 'route'], group_wait: '10s', group_interval: '1m', repeat_interval: '2h' },
    receivers: [{ name: 'operations-email', email_configs: [{ to: c.to, send_resolved: true }] }]
  };
  if (c.forceImplicitTls) config.global.smtp_force_implicit_tls = true;
  fs.writeFileSync(output, JSON.stringify(config, null, 2) + '\n', { mode: 0o600 });
  console.log('Private Alertmanager config rendered (JSON is valid YAML). Run amtool check-config before starting. No mail sent.');
} else throw Error('Select --init or --render');
