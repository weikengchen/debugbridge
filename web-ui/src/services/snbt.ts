/**
 * Minimal SNBT (Stringified NBT) parser.
 *
 * Parses Minecraft's SNBT format into a tree of SnbtNode objects.
 * Handles: compounds {k:v}, lists [...], strings "...", numbers (with
 * type suffixes like 1b, 3s, 5L, 1.0f, 2.0d), and booleans (1b/0b).
 */

import type { SnbtNode } from '../components/inspector/SnbtTree.vue'

export interface LoreSegment {
  text: string
  color?: string
  bold?: boolean
  italic?: boolean
  strikethrough?: boolean
  underlined?: boolean
  obfuscated?: boolean
}

export interface LoreLine {
  segments: LoreSegment[]
}

const MC_COLORS: Record<string, string> = {
  black: '#000000',
  dark_blue: '#0000AA',
  dark_green: '#00AA00',
  dark_aqua: '#00AAAA',
  dark_red: '#AA0000',
  dark_purple: '#AA00AA',
  gold: '#FFAA00',
  gray: '#AAAAAA',
  dark_gray: '#555555',
  blue: '#5555FF',
  green: '#55FF55',
  aqua: '#55FFFF',
  red: '#FF5555',
  light_purple: '#FF55FF',
  yellow: '#FFFF55',
  white: '#FFFFFF',
}

export function mcColorToHex(name: string): string {
  if (name.startsWith('#')) return name
  return MC_COLORS[name] ?? '#AAAAAA'
}

export function extractLoreLines(snbtStr: string): LoreLine[] {
  const root = parseSnbt(snbtStr)
  if (root.type !== 'list' || !root.children) return []
  return root.children.map(lineNode => extractComponent(lineNode))
}

function extractComponent(node: SnbtNode): LoreLine {
  const segments: LoreSegment[] = []

  if (node.type === 'compound' && node.children) {
    const rootSeg = extractSegment(node)
    if (rootSeg.text) segments.push(rootSeg)

    const extraChild = node.children.find(c => c.key === 'extra')
    if (extraChild?.type === 'list' && extraChild.children) {
      for (const child of extraChild.children) {
        if (child.type === 'compound' && child.children) {
          const seg = extractSegment(child)
          if (!seg.color && rootSeg.color) seg.color = rootSeg.color
          segments.push(seg)
        }
      }
    }
  } else if (node.type === 'string') {
    segments.push({ text: unquoteSnbt(node.value ?? '') })
  }

  return { segments }
}

function extractSegment(compound: SnbtNode): LoreSegment {
  const children = compound.children ?? []
  const get = (key: string) => children.find(c => c.key === key)?.value
  return {
    text: unquoteSnbt(get('text') ?? ''),
    color: get('color') ? unquoteSnbt(get('color')!) : undefined,
    bold: get('bold') === 'true',
    italic: get('italic') === 'true',
    strikethrough: get('strikethrough') === 'true',
    underlined: get('underlined') === 'true',
    obfuscated: get('obfuscated') === 'true',
  }
}

function unquoteSnbt(val: string): string {
  if (val.startsWith('"') && val.endsWith('"')) return val.slice(1, -1)
  return val
}

export function parseSnbt(input: string): SnbtNode {
  const parser = new SnbtParser(input.trim())
  return parser.parseValue()
}

class SnbtParser {
  private pos = 0

  constructor(private readonly src: string) {}

  parseValue(): SnbtNode {
    this.skipWhitespace()
    if (this.pos >= this.src.length) return { type: 'raw', value: '' }

    const ch = this.src[this.pos]

    if (ch === '{') return this.parseCompound()
    if (ch === '[') return this.parseList()
    if (ch === '"') return this.parseString()
    if (ch === "'") return this.parseSingleQuoteString()

    // Number, boolean, or unquoted string
    return this.parsePrimitive()
  }

  private parseCompound(): SnbtNode {
    this.expect('{')
    this.skipWhitespace()

    const children: SnbtNode[] = []

    while (this.pos < this.src.length && this.src[this.pos] !== '}') {
      this.skipWhitespace()
      const key = this.parseKey()
      this.skipWhitespace()
      this.expect(':')
      this.skipWhitespace()
      const child = this.parseValue()
      child.key = key
      children.push(child)
      this.skipWhitespace()
      if (this.src[this.pos] === ',') this.pos++
    }

    if (this.pos < this.src.length) this.pos++ // skip '}'
    return { type: 'compound', children }
  }

  private parseList(): SnbtNode {
    this.expect('[')
    this.skipWhitespace()

    // Check for typed array prefixes like [B;, [I;, [L;
    if (this.pos + 1 < this.src.length &&
        'BIL'.includes(this.src[this.pos]) &&
        this.src[this.pos + 1] === ';') {
      this.pos += 2 // skip type prefix
      this.skipWhitespace()
    }

    const children: SnbtNode[] = []
    let idx = 0

    while (this.pos < this.src.length && this.src[this.pos] !== ']') {
      this.skipWhitespace()
      const child = this.parseValue()
      child.key = String(idx++)
      children.push(child)
      this.skipWhitespace()
      if (this.src[this.pos] === ',') this.pos++
    }

    if (this.pos < this.src.length) this.pos++ // skip ']'
    return { type: 'list', children }
  }

  private parseString(): SnbtNode {
    this.expect('"')
    let result = ''
    while (this.pos < this.src.length && this.src[this.pos] !== '"') {
      if (this.src[this.pos] === '\\' && this.pos + 1 < this.src.length) {
        this.pos++
        result += this.src[this.pos]
      } else {
        result += this.src[this.pos]
      }
      this.pos++
    }
    if (this.pos < this.src.length) this.pos++ // skip closing "
    return { type: 'string', value: `"${result}"` }
  }

  private parseSingleQuoteString(): SnbtNode {
    this.expect("'")
    let result = ''
    while (this.pos < this.src.length && this.src[this.pos] !== "'") {
      if (this.src[this.pos] === '\\' && this.pos + 1 < this.src.length) {
        this.pos++
        result += this.src[this.pos]
      } else {
        result += this.src[this.pos]
      }
      this.pos++
    }
    if (this.pos < this.src.length) this.pos++ // skip closing '
    return { type: 'string', value: `"${result}"` }
  }

  private parsePrimitive(): SnbtNode {
    const start = this.pos
    // Read until delimiter — but stop at { or [ since those start nested structures
    while (this.pos < this.src.length && !',}] \t\n\r{['.includes(this.src[this.pos])) {
      this.pos++
    }
    const token = this.src.slice(start, this.pos)

    // If followed by { or [, it's a prefixed compound/list (e.g. "empty{...}" or "class_123[...]")
    if (this.pos < this.src.length && (this.src[this.pos] === '{' || this.src[this.pos] === '[')) {
      const inner = this.parseValue() // parse the { } or [ ]
      // Attach the prefix as metadata — wrap in a compound with label
      if (token && inner.type === 'compound') {
        return { ...inner, key: inner.key, value: token }
      }
      if (token && inner.type === 'list') {
        return { ...inner, key: inner.key, value: token }
      }
      return inner
    }

    // Boolean: 1b = true, 0b = false
    if (token === '1b') return { type: 'boolean', value: 'true' }
    if (token === '0b') return { type: 'boolean', value: 'false' }

    // Numbers with type suffixes
    if (/^-?\d+[bBsSlL]$/.test(token)) {
      return { type: 'number', value: token }
    }
    if (/^-?\d+(\.\d+)?[fFdD]$/.test(token)) {
      return { type: 'number', value: token }
    }
    if (/^-?\d+(\.\d+)?$/.test(token)) {
      return { type: 'number', value: token }
    }

    // Unquoted string (identifiers, resource locations, etc.)
    return { type: 'string', value: token }
  }

  private parseKey(): string {
    this.skipWhitespace()
    if (this.src[this.pos] === '"') {
      // Quoted key
      this.pos++ // skip opening "
      let key = ''
      while (this.pos < this.src.length && this.src[this.pos] !== '"') {
        if (this.src[this.pos] === '\\' && this.pos + 1 < this.src.length) {
          this.pos++
        }
        key += this.src[this.pos]
        this.pos++
      }
      if (this.pos < this.src.length) this.pos++ // skip closing "
      return key
    }

    // Unquoted key: read until ':'
    const start = this.pos
    while (this.pos < this.src.length && this.src[this.pos] !== ':') {
      this.pos++
    }
    return this.src.slice(start, this.pos).trim()
  }

  private skipWhitespace(): void {
    while (this.pos < this.src.length && ' \t\n\r'.includes(this.src[this.pos])) {
      this.pos++
    }
  }

  private expect(ch: string): void {
    if (this.pos < this.src.length && this.src[this.pos] === ch) {
      this.pos++
    }
  }
}
