# images for process

- Do not process the same image twice
- Create a mechanism to retry for errors in processing
- Do not continue until all images were processed

# images for recipe

- each recipe should have only one small image anime like
- this image should have no people, only food related stuff
- to keep it simple, the image name should have the same recipe json file name
- for the title create a bigger image anime like of an italian old lady and all food categories of this book together 

# Processed JSON

- the main language should be Brazilian Portuguese, if it is not a name you need to translate it
- do not repeat ingredients
- do not use full words in capital letters
- remove from index these words: yield and kids
- remove from index these symbols: } , " { 

# Files structure

recipes/pending - images pending process
recipes/json - json of recipes processed
recipes/processed - images already processed
recipes/images - generated images for each recipe

# PDF generation

- This PDF generation is for a recipes book
- The final PDF generated must have file name and timestamp: "receitas-da-ester-YYYY-MM-DD-HH_MI.pdf"
- This PDF must have a clickable index to all recipes
- The main language is Brazilian Portuguese
- The books title is "Receitas da Ester"
- Use a small font
- Write two recipes by page, using this format: 
  [recipe 1] [image 1]
  [image 2] [recipe 2] 
- Avoid repeating numbers in listing, like: "1. 1." or "2. 2."
- If the list has only one item, do not start the sentence with 1 
- Categorize all recipes, do not create an "Others / Outros" category
- Do not repeat recipe names
- Before the index create a table summary counting all recipes by category 