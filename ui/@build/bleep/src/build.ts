import * as fs from 'node:fs';
import * as path from 'node:path';
import * as cps from 'node:child_process';
import * as ps from 'node:process';
import { parseModules } from './parse';
import { makeBleepConfig, typescriptWatch } from './tsc';
import { sassWatch } from './sass';
import { esbuildWatch } from './esbuild';
import { LichessModule, env, errorMark, colors as c } from './main';

export let moduleDeps: Map<string, string[]>;
export let modules: Map<string, LichessModule>;

let startTime: number | undefined = Date.now();

export async function build(moduleNames: string[]) {
  env.log(`Parsing modules in '${c.cyan(env.uiDir)}'`);

  ps.chdir(env.uiDir);

  [modules, moduleDeps] = await parseModules();

  if (moduleNames.find(x => !known(x))) {
    env.log(`${errorMark} - unknown module '${c.magenta(moduleNames.find(x => !known(x))!)}'`);
    return;
  }

  buildDependencyList();

  const buildModules = moduleNames.length === 0 ? [...modules.values()] : depsMany(moduleNames);

  await fs.promises.mkdir(env.jsDir, { recursive: true });
  await fs.promises.mkdir(env.cssDir, { recursive: true });

  if (env.sass) sassWatch();

  await makeBleepConfig(buildModules);
  typescriptWatch(() => {
    if (env.esbuild) esbuildWatch(buildModules);
  });
}

export function resetTimer(clear = false) {
  if (!startTime) startTime = Date.now();
  if (clear) startTime = undefined;
}

export function bundleDone(n: number) {
  if (!startTime) return; // only do this once, everything happens so fast on rebuilds
  const results = n > 0 ? `Built ${n} module${n > 1 ? 's' : ''}` : 'Done';
  const elapsed = startTime ? `in ${c.green((Date.now() - startTime) / 1000 + '')}s ` : '';
  env.log(`${results} ${elapsed}`);
  startTime = undefined;
}

export function preModule(mod: LichessModule | undefined) {
  mod?.build.forEach((args: string[]) => {
    env.log(`[${c.grey(mod.name)}] exec - ${c.cyanBold(args.join(' '))}`);
    const stdout = cps.execSync(`${args.join(' ')}`, { cwd: mod.root });
    if (stdout) env.log(stdout, { ctx: mod.name });
  });
  if (mod?.copyMe)
    for (const cp of mod.copyMe) {
      const sources: string[] = [];
      const dest = path.join(env.rootDir, cp.dest) + path.sep;
      if (typeof cp.src === 'string') {
        sources.push(path.join(env.nodeDir, cp.src));
        env.log(`[${c.grey(mod.name)}] copy '${c.cyan(cp.src)}'`);
      } else if (Array.isArray(cp.src)) {
        for (const s of cp.src) {
          sources.push(path.join(env.nodeDir, s));
          env.log(`[${c.grey(mod.name)}] copy '${c.cyan(s)}'`);
        }
      }
      fs.mkdirSync(dest, { recursive: true });

      cps.execFileSync('cp', ['-rf', ...sources, dest]);
    }
}

function buildDependencyList() {}

function depsOne(modName: string): LichessModule[] {
  const collect = (dep: string): string[] => [...(moduleDeps.get(dep) || []).flatMap(d => collect(d)), dep];
  return unique(collect(modName).map(name => modules.get(name)));
}

const depsMany = (modNames: string[]): LichessModule[] => unique(modNames.flatMap(depsOne));

const unique = <T>(mods: (T | undefined)[]): T[] => [...new Set(mods.filter(x => x))] as T[];

const known = (name: string): boolean => modules.has(name);
