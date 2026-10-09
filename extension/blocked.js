const q = new URLSearchParams(location.search);
const name = q.get('name');
const why = {
  sub: `r/${name} is marked adult-only by Reddit.`,
  user: `u/${name} is a profile marked adult-only by Reddit.`,
  gate: 'Adult content gates are blocked.',
  host: 'This site hosts adult content.',
}[q.get('reason')] || '';
document.getElementById('why').textContent = why;
