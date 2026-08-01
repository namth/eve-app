import * as FileSystem from 'expo-file-system';
import { Platform } from 'react-native';
import { PersonProfile, UserRole } from '../types/personProfile';

const PEOPLE_STORAGE_KEY = '@eve_people_db';
const CURRENT_USER_STORAGE_KEY = '@eve_current_person';

let memoryPeople: PersonProfile[] = [];
let memoryCurrentUser: PersonProfile | null = null;
let isLoaded = false;

// Storage engine bền vững chuẩn Expo FileSystem (Android/iOS) + localStorage (Web)
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
      const content = await FileSystem.readAsStringAsync(filePath);
      return content;
    }
  } catch (e) {
    console.warn('[Storage] FileSystem.getItem error:', e);
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
    console.warn('[Storage] FileSystem.setItem error:', e);
  }
}

async function storageRemoveItem(key: string): Promise<void> {
  if (Platform.OS === 'web') {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.removeItem(key);
    }
    return;
  }

  try {
    const filename = key.replace(/[^a-zA-Z0-9_-]/g, '_') + '.json';
    const filePath = `${FileSystem.documentDirectory}${filename}`;
    const info = await FileSystem.getInfoAsync(filePath);
    if (info.exists) {
      await FileSystem.deleteAsync(filePath, { idempotent: true });
    }
  } catch (e) {
    console.warn('[Storage] FileSystem.removeItem error:', e);
  }
}

// Calculation for Cosine Similarity between 2 embedding vectors
function calculateCosineSimilarity(v1: number[], v2: number[]): number {
  if (!v1 || !v2 || v1.length === 0 || v1.length !== v2.length) return 0;
  let dotProduct = 0;
  let mag1 = 0;
  let mag2 = 0;
  for (let i = 0; i < v1.length; i++) {
    dotProduct += v1[i] * v2[i];
    mag1 += v1[i] * v1[i];
    mag2 += v2[i] * v2[i];
  }
  if (mag1 === 0 || mag2 === 0) return 0;
  return dotProduct / (Math.sqrt(mag1) * Math.sqrt(mag2));
}

export const peopleDatabaseService = {
  /**
   * Lấy toàn bộ danh sách người dùng đã đăng ký
   */
  async getPeopleList(): Promise<PersonProfile[]> {
    try {
      if (isLoaded && memoryPeople.length > 0) {
        return [...memoryPeople];
      }

      const jsonVal = await storageGetItem(PEOPLE_STORAGE_KEY);
      if (jsonVal) {
        const list = JSON.parse(jsonVal) as PersonProfile[];
        if (Array.isArray(list)) {
          memoryPeople = list;
          isLoaded = true;
          console.log('[PeopleDB] Loaded people list from storage:', memoryPeople.length);
          return [...memoryPeople];
        }
      }
      isLoaded = true;
      return [...memoryPeople];
    } catch (err) {
      console.warn('[PeopleDB] Error loading people list:', err);
      return [...memoryPeople];
    }
  },

  /**
   * Lưu hoặc cập nhật hồ sơ người dùng (Bao gồm ảnh avatar_base64)
   */
  async savePersonProfile(person: PersonProfile): Promise<PersonProfile> {
    await this.getPeopleList();

    const idx = memoryPeople.findIndex((p) => p.id === person.id);
    const updatedPerson: PersonProfile = {
      ...person,
      last_seen_at: Date.now(),
      role: person.role || 'friend',
    };

    if (idx >= 0) {
      memoryPeople[idx] = updatedPerson;
    } else {
      memoryPeople.push(updatedPerson);
    }

    await this.persistToStorage();
    await this.saveCurrentUser(updatedPerson);
    console.log('[PeopleDB] Saved person profile with Avatar Base64:', updatedPerson.name, 'Role:', updatedPerson.role);
    return updatedPerson;
  },

  /**
   * Đồng bộ hồ sơ người dùng từ n8n response (Kèm Diff Check: CHỈ CẬP NHẬT KHI CÓ THAY ĐỔI)
   */
  async syncPersonFromResponse(
    incomingPerson: {
      name?: string;
      age?: number;
      gender?: 'male' | 'female' | 'unknown';
      preferred_pronoun?: string;
      role?: UserRole;
    },
    activeCurrentPerson?: PersonProfile | null
  ): Promise<{ person: PersonProfile; isUpdated: boolean }> {
    if (!incomingPerson || !incomingPerson.name) {
      throw new Error('Invalid incoming person data');
    }

    const people = await this.getPeopleList();
    const cleanName = incomingPerson.name.trim();

    // Tìm kiếm người quen trong CSDL local theo Tên (Case-insensitive) hoặc ID hiện tại
    let match = people.find(
      (p) =>
        p.name.toLowerCase() === cleanName.toLowerCase() ||
        (activeCurrentPerson && p.id === activeCurrentPerson.id)
    );

    if (match) {
      // DIFF CHECK: Kiểm tra xem có bất kỳ thông tin nào THAY ĐỔI không
      const hasNameDiff = incomingPerson.name && incomingPerson.name !== match.name;
      const hasAgeDiff = incomingPerson.age !== undefined && incomingPerson.age !== match.age;
      const hasGenderDiff = incomingPerson.gender && incomingPerson.gender !== match.gender;
      const hasPronounDiff = incomingPerson.preferred_pronoun && incomingPerson.preferred_pronoun !== match.preferred_pronoun;
      const hasRoleDiff = incomingPerson.role && incomingPerson.role !== match.role;

      const hasChange = hasNameDiff || hasAgeDiff || hasGenderDiff || hasPronounDiff || hasRoleDiff;

      if (!hasChange) {
        console.log(`[PeopleDB] Person data UNCHANGED for '${match.name}'. Skipping disk update!`);
        await this.saveCurrentUser(match);
        return { person: match, isUpdated: false };
      }

      // CÓ THAY ĐỔI ➔ Cập nhật thông tin mới
      const updated: PersonProfile = {
        ...match,
        name: incomingPerson.name || match.name,
        age: incomingPerson.age !== undefined ? incomingPerson.age : match.age,
        gender: incomingPerson.gender || match.gender,
        preferred_pronoun: incomingPerson.preferred_pronoun || match.preferred_pronoun,
        role: incomingPerson.role || match.role,
        last_seen_at: Date.now(),
      };

      const saved = await this.savePersonProfile(updated);
      console.log(`[PeopleDB] UPDATED person profile for '${saved.name}' due to detected diffs.`);
      return { person: saved, isUpdated: true };
    } else {
      // CHƯA CÓ TRONG CSDL LOCAL ➔ Thêm mới vào Danh sách Bạn bè (role: friend)
      const newPerson: PersonProfile = {
        id: `person_${Date.now()}`,
        name: cleanName,
        age: incomingPerson.age,
        gender: incomingPerson.gender || 'unknown',
        preferred_pronoun: incomingPerson.preferred_pronoun || 'Anh',
        role: incomingPerson.role || (cleanName.toLowerCase() === 'nam' ? 'admin' : 'friend'),
        created_at: Date.now(),
        last_seen_at: Date.now(),
      };

      const saved = await this.savePersonProfile(newPerson);
      console.log(`[PeopleDB] ENROLLED NEW person profile for '${saved.name}' into friends list.`);
      return { person: saved, isUpdated: true };
    }
  },

  /**
   * Lưu thông tin người dùng hiện tại đang đàm thoại (Current User)
   */
  async saveCurrentUser(person: PersonProfile | null): Promise<void> {
    memoryCurrentUser = person;
    try {
      if (person) {
        await storageSetItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(person));
      } else {
        await storageRemoveItem(CURRENT_USER_STORAGE_KEY);
      }
    } catch (e) {
      console.warn('[PeopleDB] Error saving current user:', e);
    }
  },

  /**
   * Lấy thông tin người dùng hiện tại (Current User)
   */
  async getCurrentUser(): Promise<PersonProfile | null> {
    if (memoryCurrentUser) return memoryCurrentUser;
    try {
      const jsonVal = await storageGetItem(CURRENT_USER_STORAGE_KEY);
      if (jsonVal) {
        memoryCurrentUser = JSON.parse(jsonVal) as PersonProfile;
        return memoryCurrentUser;
      }
    } catch (e) {}
    return null;
  },

  /**
   * Tìm kiếm người quen dựa trên vector sinh trắc học (Face embedding / Voice embedding)
   */
  async findMatchingPerson(
    faceVector?: number[],
    voiceVector?: number[]
  ): Promise<PersonProfile | null> {
    const people = await this.getPeopleList();
    if (!people || people.length === 0) return null;

    let bestMatch: PersonProfile | null = null;
    let highestSim = 0;

    for (const p of people) {
      let sim = 0;
      if (faceVector && p.face_embedding) {
        sim = calculateCosineSimilarity(faceVector, p.face_embedding);
      } else if (voiceVector && p.voice_embedding) {
        sim = calculateCosineSimilarity(voiceVector, p.voice_embedding);
      }

      if (sim > 0.75 && sim > highestSim) {
        highestSim = sim;
        bestMatch = p;
      }
    }

    if (bestMatch) {
      console.log(`[PeopleDB] Matched person: ${bestMatch.name} (Similarity: ${(highestSim * 100).toFixed(1)}%)`);
      bestMatch.last_seen_at = Date.now();
      await this.savePersonProfile(bestMatch);
    }

    return bestMatch;
  },

  /**
   * Cập nhật Role cho một người (Friend <-> Admin)
   */
  async updatePersonRole(personId: string, newRole: UserRole): Promise<boolean> {
    const people = await this.getPeopleList();
    const person = people.find((p) => p.id === personId);
    if (!person) return false;

    person.role = newRole;
    await this.savePersonProfile(person);
    console.log(`[PeopleDB] Updated role for ${person.name} -> ${newRole}`);
    return true;
  },

  /**
   * Xóa hồ sơ người dùng khỏi CSDL
   */
  async deletePersonProfile(personId: string): Promise<boolean> {
    await this.getPeopleList();
    memoryPeople = memoryPeople.filter((p) => p.id !== personId);
    await this.persistToStorage();
    console.log('[PeopleDB] Deleted person profile:', personId);
    return true;
  },

  /**
   * Helper nội bộ để lưu vào AsyncStorage
   */
  async persistToStorage(): Promise<void> {
    try {
      await storageSetItem(PEOPLE_STORAGE_KEY, JSON.stringify(memoryPeople));
      console.log('[PeopleDB] Persisted people list to storage. Total:', memoryPeople.length);
    } catch (e) {
      console.warn('[PeopleDB] Error persisting to storage:', e);
    }
  },
};
