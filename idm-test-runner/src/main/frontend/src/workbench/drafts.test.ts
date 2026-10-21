import { describe, expect, it } from 'vitest';
import { deleteDraft, draftKey, listDrafts, readDraft, writeDraft } from './drafts';

const WS = '/home/ana/lab-tests';

describe('drafts', () => {
  it('keys a draft by workspace and file, separated by a NUL', () => {
    expect(draftKey(WS, 'scenarios/a.yaml')).toBe('/home/ana/lab-tests\u0000scenarios/a.yaml');
  });

  it('keeps a draft across connections (as across a reload) and deletes it', async () => {
    expect(await readDraft(WS, 'scenarios/a.yaml')).toBeNull();
    await writeDraft(WS, 'scenarios/a.yaml', { content: 'name: edited\n', baseVersion: 'v1', savedAt: 1000 });

    expect(await readDraft(WS, 'scenarios/a.yaml')).toEqual({ content: 'name: edited\n', baseVersion: 'v1', savedAt: 1000 });

    await deleteDraft(WS, 'scenarios/a.yaml');
    expect(await readDraft(WS, 'scenarios/a.yaml')).toBeNull();
  });

  it('stores them in the idmtest database, drafts store, under the documented key', async () => {
    await writeDraft(WS, 'config/application-lab.yml', { content: 'x: 1\n', baseVersion: 'v2', savedAt: 5 });

    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('idmtest');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const value = await new Promise<unknown>((resolve) => {
      const request = db.transaction('drafts').objectStore('drafts').get(`${WS}\u0000config/application-lab.yml`);
      request.onsuccess = () => resolve(request.result);
    });
    db.close();
    expect(value).toEqual({ content: 'x: 1\n', baseVersion: 'v2', savedAt: 5 });
  });

  it("lists one workspace's drafted files only", async () => {
    await writeDraft(WS, 'scenarios/b.yaml', { content: 'b', baseVersion: 'v', savedAt: 1 });
    await writeDraft(WS, 'scenarios/a.yaml', { content: 'a', baseVersion: 'v', savedAt: 1 });
    await writeDraft('/home/ana/lab-tests-2', 'scenarios/c.yaml', { content: 'c', baseVersion: 'v', savedAt: 1 });

    expect(await listDrafts(WS)).toEqual(['scenarios/a.yaml', 'scenarios/b.yaml']);
  });

  it('without IndexedDB (a private window), reads nothing and writes nothing, without failing', async () => {
    const saved = globalThis.indexedDB;
    // @ts-expect-error simulating a browser without IndexedDB
    delete globalThis.indexedDB;
    try {
      await writeDraft(WS, 'scenarios/a.yaml', { content: 'a', baseVersion: 'v', savedAt: 1 });
      expect(await readDraft(WS, 'scenarios/a.yaml')).toBeNull();
      expect(await listDrafts(WS)).toEqual([]);
      await deleteDraft(WS, 'scenarios/a.yaml');
    } finally {
      globalThis.indexedDB = saved;
    }
  });
});
