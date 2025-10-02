import type { BrokkEvent, BufferItem } from './types';
import type { SymbolLookupResult } from './stores/symbolCacheStore';
import type { EnvInfo } from './stores/envStore';
import type { FilePathLookupResult } from './stores/filePathCacheStore';

export {};

declare global {
  interface Window {
    brokk: {
      _buffer: BufferItem[];
      onEvent: (payload: BrokkEvent) => Promise<void>;
      getSelection: () => string;
      clear: () => void;
      setTheme: (dark: boolean, isDevMode?: boolean, wrapMode?: boolean, zoom?: number) => void;
      showSpinner: (message?: string) => void;
      hideSpinner: () => void;

      // Search API
      setSearch: (query: string, caseSensitive: boolean) => void;
      clearSearch: () => void;
      nextMatch: () => void;
      prevMatch: () => void;
      scrollToCurrent: () => void;
      getSearchState: () => { total: number; current: number; query: string; caseSensitive: boolean; };

      // Symbol lookup API
      refreshSymbolLookup: (contextId?: string) => void;
      onSymbolLookupResponse?: (results: Record<string, SymbolLookupResult>, seq: number, contextId: string) => void;

      // File path lookup API
      refreshFilePathLookup: (contextId?: string) => void;
      onFilePathLookupResponse?: (results: Record<string, FilePathLookupResult>, seq: number, contextId: string) => void;

      // Environment info API
      setEnvironmentInfo: (info: EnvInfo) => void;
    };
    javaBridge?: {
      onAck: (epoch: number) => void;
      jsLog: (level: string, message: string) => void;
      searchStateChanged: (total: number, current: number) => void;
      onSymbolClick: (symbolName: string, symbolExists: boolean, symbolFqn: string | null, x: number, y: number) => void;
      onFilePathClick: (filePath: string, exists: boolean, matchesJson: string, x: number, y: number) => void;
      captureText:(text: string) => void;
      deleteHistoryTask?: (sequence: number) => void;
      lookupSymbolsAsync?: (symbolNamesJson: string, seq: number | null, contextId: string) => void;
      lookupFilePathsAsync?: (filePathsJson: string, seq: number, contextId: string) => void;
    };
  }
}
