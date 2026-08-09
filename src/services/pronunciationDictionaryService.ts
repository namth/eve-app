import * as FileSystem from 'expo-file-system';
import { Platform } from 'react-native';

export interface PronunciationItem {
  word: string;
  speak: string;
  updated_at: number;
}

const PRONUNCIATION_STORAGE_KEY = '@eve_pronunciation_db';

let memoryPronunciations: Record<string, PronunciationItem> = {};
let isLoaded = false;

// Helper lưu trữ FileSystem bền vững cho Native & Web
async function storageGetItem(key: string): Promise<string | null> {
  if (Platform.OS === 'web') {
    if (typeof window !== 'undefined' && window.localStorage) {
      return window.localStorage.getItem(key);
    }
    return null;
  }
  try {
    const filename = key.replace(/[^a-zA-Z0-9_-]/g, '_') + '.json';
    const filePath = `${FileSystem.documentDirectory}${filename}`;
    const info = await FileSystem.getInfoAsync(filePath);
    if (info.exists) {
      return await FileSystem.readAsStringAsync(filePath);
    }
  } catch (e) {
    console.warn('[PronunciationDB] FileSystem getItem error:', e);
  }
  return null;
}

async function storageSetItem(key: string, value: string): Promise<void> {
  if (Platform.OS === 'web') {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.setItem(key, value);
    }
    return;
  }
  try {
    const filename = key.replace(/[^a-zA-Z0-9_-]/g, '_') + '.json';
    const filePath = `${FileSystem.documentDirectory}${filename}`;
    await FileSystem.writeAsStringAsync(filePath, value);
  } catch (e) {
    console.warn('[PronunciationDB] FileSystem setItem error:', e);
  }
}

export const pronunciationDictionaryService = {
  /**
   * Tải danh sách Từ điển Phát âm từ đĩa local
   */
  async loadDictionary(): Promise<Record<string, PronunciationItem>> {
    if (isLoaded) return memoryPronunciations;
    try {
      const raw = await storageGetItem(PRONUNCIATION_STORAGE_KEY);
      if (raw) {
        memoryPronunciations = JSON.parse(raw);
        console.log(`[PronunciationDB] Loaded ${Object.keys(memoryPronunciations).length} learned words from local disk.`);
      }
    } catch (e) {
      console.warn('[PronunciationDB] Error loading dictionary:', e);
    }
    isLoaded = true;
    return memoryPronunciations;
  },

  /**
   * Lấy danh sách phát âm dưới dạng mảng để hiển thị UI
   */
  async getPronunciationList(): Promise<PronunciationItem[]> {
    const dict = await this.loadDictionary();
    return Object.values(dict).sort((a, b) => b.updated_at - a.updated_at);
  },

  /**
   * Lưu hoặc cập nhật một từ phát âm mới
   */
  async savePronunciation(word: string, speak: string): Promise<PronunciationItem> {
    if (!word || !speak) {
      throw new Error('Word và Speak không được để trống.');
    }

    const dict = await this.loadDictionary();
    const cleanKey = word.trim().toLowerCase();
    const entry: PronunciationItem = {
      word: word.trim(),
      speak: speak.trim(),
      updated_at: Date.now(),
    };

    dict[cleanKey] = entry;
    memoryPronunciations = { ...dict };

    await storageSetItem(PRONUNCIATION_STORAGE_KEY, JSON.stringify(memoryPronunciations));
    console.log(`[PronunciationDB] SAVED learned pronunciation: '${cleanKey}' -> '${entry.speak}'`);
    return entry;
  },

  /**
   * Xóa một từ phát âm ra khỏi từ điển
   */
  async deletePronunciation(word: string): Promise<void> {
    const dict = await this.loadDictionary();
    const cleanKey = word.trim().toLowerCase();

    if (dict[cleanKey]) {
      delete dict[cleanKey];
      memoryPronunciations = { ...dict };
      await storageSetItem(PRONUNCIATION_STORAGE_KEY, JSON.stringify(memoryPronunciations));
      console.log(`[PronunciationDB] DELETED learned pronunciation: '${cleanKey}'`);
    }
  },

  /**
   * Thay thế các từ học được trong đoạn văn thoại (Ưu tiên hàng đầu, case-insensitive)
   */
  async applyLearnedDictionary(text: string): Promise<string> {
    if (!text) return '';
    const dict = await this.loadDictionary();
    const keys = Object.keys(dict);

    if (keys.length === 0) return text;

    let result = text;
    for (const key of keys) {
      const entry = dict[key];
      if (!entry || !entry.speak) continue;

      // Escaping special characters in word/domain for regex
      const escapedKey = entry.word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const regex = new RegExp(escapedKey, 'gi');

      result = result.replace(regex, entry.speak);
    }

    return result;
  },
};
