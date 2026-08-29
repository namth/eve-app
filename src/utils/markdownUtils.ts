/**
 * Tiện ích loại bỏ cú pháp Markdown (Markdown Stripper)
 * Đảm bảo mọi văn bản thông báo hoặc nội dung phản hồi trước khi đọc qua TTS
 * đều được làm sạch các ký tự đặc biệt của Markdown (*, #, _, `, >, [, ], ~, |...),
 * giúp giọng đọc phát âm tự nhiên, trôi chảy và không bị đọc các ký tự thừa.
 */

export function stripMarkdown(text: string): string {
  if (!text || typeof text !== 'string') return '';

  let output = text;

  // 1. Loại bỏ Code blocks (```code```)
  output = output.replace(/```(?:[a-zA-Z0-9_-]+)?\s*([\s\S]*?)\s*```/g, '$1');

  // 2. Loại bỏ Inline code (`code`)
  output = output.replace(/`([^`]+)`/g, '$1');

  // 3. Loại bỏ Images: ![alt](url) -> alt (nếu không có alt thì bỏ trống)
  output = output.replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1');

  // 4. Chuyển đổi Links: [title](url) -> title
  output = output.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1');

  // 5. Chuyển đổi Reference links: [title][id] hoặc [title][] -> title
  output = output.replace(/\[([^\]]+)\]\[[^\]]*\]/g, '$1');

  // 6. Loại bỏ HTML tags: <b>, <div>, <br/>, v.v.
  output = output.replace(/<[^>]+>/g, ' ');

  // 7. Loại bỏ Headers (# Header, ## Header, ...)
  output = output.replace(/^\s*#{1,6}\s+/gm, '');

  // 8. Loại bỏ Blockquotes (> Quote)
  output = output.replace(/^\s*>\s*/gm, '');

  // 9. Loại bỏ Horizontal Rules (---, ***, ___)
  output = output.replace(/^\s*[-*_]{3,}\s*$/gm, '');

  // 10. Xử lý Task lists: - [ ] hoặc - [x]
  output = output.replace(/^\s*[-*+]\s+\[[ xX]\]\s*/gm, '');

  // 11. Xử lý Unordered list bullets (*, -, +) ở đầu dòng
  output = output.replace(/^\s*[-*+]\s+/gm, '');

  // 12. Xử lý Ordered lists (1. , 2. ) ở đầu dòng
  output = output.replace(/^\s*\d+\.\s+/gm, '');

  // 13. Xử lý Bold & Italic kết hợp (***text*** hoặc ___text___)
  output = output.replace(/\*\*\*([^*]+)\*\*\*/g, '$1');
  output = output.replace(/___([^_]+)___/g, '$1');

  // 14. Xử lý Bold (**text** hoặc __text__)
  output = output.replace(/\*\*([^*]+)\*\*/g, '$1');
  output = output.replace(/__([^_]+)__/g, '$1');

  // 15. Xử lý Italic (*text* hoặc _text_)
  output = output.replace(/\*([^*]+)\*/g, '$1');
  output = output.replace(/(^|\s)_([^_]+)_(\s|$)/g, '$1$2$3');

  // 16. Xử lý Strikethrough (~~text~~)
  output = output.replace(/~~([^~]+)~~/g, '$1');

  // 17. Xử lý Table formatting:
  // Loại bỏ dòng phân cách bảng: |---|---|
  output = output.replace(/^\s*\|?[\s:-|-]+$/gm, '');
  // Loại bỏ pipe ở đầu và cuối dòng
  output = output.replace(/^\s*\|\s*/gm, '');
  output = output.replace(/\s*\|\s*$/gm, '');
  // Thay thế các ký tự | ở giữa bằng dấu phẩy
  output = output.replace(/\s*\|\s*/g, ', ');

  // 18. Dọn dẹp ngoặc vuông độc lập [ và ] (ví dụ: [Thông báo] -> Thông báo)
  output = output.replace(/[\[\]]/g, '');

  // 19. Dọn dẹp các ký tự markdown lẻ loi còn sót lại
  output = output.replace(/[*~`#_>]/g, ' ');

  // 20. Xử lý các dòng và kết nối câu tự nhiên
  const lines = output
    .split('\n')
    .map((line) => {
      let l = line.trim();
      // Xóa dấu phẩy thừa ở đầu hoặc cuối dòng
      l = l.replace(/^,\s*/, '').replace(/,\s*$/, '');
      return l;
    })
    .filter((line) => line.length > 0);

  output = lines.reduce((acc, line, idx) => {
    if (idx === 0) return line;
    const prevEndsWithPunct = /[.:!?;,]$/.test(acc);
    return prevEndsWithPunct ? `${acc} ${line}` : `${acc}. ${line}`;
  }, '');

  // 21. Dọn dẹp khoảng trắng liên tiếp
  output = output.replace(/\s+/g, ' ');

  // 22. Dọn dẹp dấu câu trùng lặp do ghép chuỗi
  output = output.replace(/\s+([.,!?:;])/g, '$1');
  output = output.replace(/([.,!?:;])\1+/g, '$1');

  return output.trim();
}
