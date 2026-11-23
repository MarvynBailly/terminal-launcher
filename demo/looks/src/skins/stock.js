/* Stock: no hooks at all, so every movement is the engine's default, which is how the app moves today. */
TL.register({
  id: 'stock',
  name: 'Stock',
  tagline: 'The launcher as it ships today',
  mood: 'Plain white-on-black terminal in IBM Plex Mono, with an amber prompt and blue app names.',
  motion: [
    'The screen opens from a bright line, like an old tube',
    '"access granted" resolves out of noise while the clock breaks apart',
    'An app grows out of its own line until it fills the screen',
  ],
  palette: { bg: '#000000', fg: '#d6d8d2', dim: '#7b8086', accent: '#f0b45e', app: '#8fbaf0', ok: '#a3d18b', err: '#f58675' },
  light: false,
  fonts: ['IBM+Plex+Mono:wght@400;500'],
  hooks: {},
});
