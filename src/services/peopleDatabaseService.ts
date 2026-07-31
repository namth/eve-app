import { PersonProfile, UserRole } from '../types/personProfile';

const PEOPLE_STORAGE_KEY = '@eve_people_db';

let memoryPeople: PersonProfile[] = [];
let isLoaded = false;

// Mock calculation for Cosine Similarity between 2 embedding vectors
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

      let AsyncStorage: any = null;
      try {
        AsyncStorage = require('@react-native-async-storage/async-storage').default;
      } catch (e) {}

      if (AsyncStorage) {
        const jsonVal = await AsyncStorage.getItem(PEOPLE_STORAGE_KEY);
        if (jsonVal) {
          const list = JSON.parse(jsonVal) as PersonProfile[];
          if (Array.isArray(list)) {
            memoryPeople = list;
            isLoaded = true;
            console.log('[PeopleDB] Loaded people list from storage:', memoryPeople.length);
            return [...memoryPeople];
          }
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
   * Lưu hoặc cập nhật hồ sơ người dùng
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
    console.log('[PeopleDB] Saved person profile:', updatedPerson.name, 'Role:', updatedPerson.role);
    return updatedPerson;
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

      // Ngưỡng nhận diện (Threshold 0.75+)
      if (sim > 0.75 && sim > highestSim) {
        highestSim = sim;
        bestMatch = p;
      }
    }

    if (bestMatch) {
      console.log(`[PeopleDB] Matched person: ${bestMatch.name} (Similarity: ${(highestSim * 100).toFixed(1)}%)`);
      // Update last seen timestamp
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
      let AsyncStorage: any = null;
      try {
        AsyncStorage = require('@react-native-async-storage/async-storage').default;
      } catch (e) {}

      if (AsyncStorage) {
        await AsyncStorage.setItem(PEOPLE_STORAGE_KEY, JSON.stringify(memoryPeople));
      }
    } catch (e) {
      console.warn('[PeopleDB] Error persisting to storage:', e);
    }
  },
};
